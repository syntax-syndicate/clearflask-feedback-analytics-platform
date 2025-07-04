package com.smotana.clearflask.core.email;

import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.*;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.kik.config.ice.ConfigSystem;
import com.kik.config.ice.annotations.DefaultValue;
import com.smotana.clearflask.api.model.*;
import com.smotana.clearflask.billing.CommonPlanVerifyStore;
import com.smotana.clearflask.billing.PlanVerifyStore;
import com.smotana.clearflask.core.ManagedService;
import com.smotana.clearflask.core.push.NotificationService;
import com.smotana.clearflask.core.push.NotificationService.Digest;
import com.smotana.clearflask.core.push.NotificationService.DigestItem;
import com.smotana.clearflask.core.push.NotificationService.DigestProject;
import com.smotana.clearflask.core.push.NotificationService.DigestSection;
import com.smotana.clearflask.store.*;
import com.smotana.clearflask.store.AccountStore.Account;
import com.smotana.clearflask.store.AccountStore.SearchAccountsResponse;
import com.smotana.clearflask.store.IdeaStore.IdeaModel;
import com.smotana.clearflask.store.NotificationStore.NotificationModel;
import com.smotana.clearflask.store.ProjectStore.Project;
import com.smotana.clearflask.util.Extern;
import com.smotana.clearflask.web.Application;
import io.dataspray.singletable.DynamoTable;
import io.dataspray.singletable.Expression;
import io.dataspray.singletable.SingleTable;
import io.dataspray.singletable.TableSchema;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.smotana.clearflask.store.UserStore.UserModel;
import static io.dataspray.singletable.TableType.Primary;

@Slf4j
@Singleton
public class WeeklyDigestService extends ManagedService {

    public interface Config {

        @DefaultValue("true")
        boolean enabled();

        /**
         * Will try to send emails on Monday at this time
         */
        @DefaultValue("9")
        int sendAtTime();

        /**
         * Will add jitter to the sendAtTime
         */
        @DefaultValue("300")
        int jitterSeconds();

        /**
         * Will try to send emails on Monday at this time
         */
        @DefaultValue("1")
        double rateLimiterPermitsPerSecond();
    }

    public enum Status {
        PROCESSING,
        COMPLETE,
    }

    @Value
    @Builder(toBuilder = true)
    @AllArgsConstructor
    @DynamoTable(type = Primary, partitionKeys = {"weekStart"}, rangePrefix = "weeklyDigestWork")
    public static class WeeklyDigestWork {
        @NonNull
        Instant weekStart;

        @NonNull
        Status status;

        @NonNull
        long ttlInEpochSec;
    }

    @Value
    @AllArgsConstructor
    private class DigestRun {
        ZonedDateTime now;
        Instant start;
        Instant end;
        RateLimiter rateLimiter = guavaRateLimiters.create(config.rateLimiterPermitsPerSecond(), 1, 1);
        LoadingCache<String, Optional<Project>> projectCache = CacheBuilder.newBuilder()
                .build(new CacheLoader<>() {
                    @Override
                    public Optional<Project> load(@NotNull String projectId) {
                        return projectStore.getProject(projectId, false);
                    }
                });

        public DigestRun() {
            this.now = now();
            this.start = getDigestStart(now);
            this.end = getDigestEnd(now);
        }

        public DigestRun(ZonedDateTime now) {
            this.now = now;
            this.start = getDigestStart(now);
            this.end = getDigestEnd(now);
        }

        public DigestRun(Instant start, Instant end) {
            this.now = now();
            this.start = getDigestStart(now);
            this.end = getDigestEnd(now);
        }
    }

    @Inject
    private Config config;
    @Inject
    private Application.Config configApp;
    @Inject
    private SingleTable singleTable;
    @Inject
    private AccountStore accountStore;
    @Inject
    private IdeaStore ideaStore;
    @Inject
    private UserStore userStore;
    @Inject
    private ProjectStore projectStore;
    @Inject
    private NotificationStore notificationStore;
    @Inject
    private NotificationService notificationService;
    @Inject
    private GuavaRateLimiters guavaRateLimiters;
    @Inject
    private PlanVerifyStore planVerifyStore;

    private TableSchema<WeeklyDigestWork> weeklyDigestWorkSchema;
    private ListeningScheduledExecutorService executor;

    @Inject
    private void setup() {
        weeklyDigestWorkSchema = singleTable.parseTableSchema(WeeklyDigestWork.class);
    }

    @Override
    protected void serviceStart() throws Exception {
        executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                .setNameFormat("WeeklyDigestService-worker-%d").build()));
        Duration nextRuntime = getNextRuntime(now(), config.sendAtTime(), config.jitterSeconds());
        log.info("Weekly digest next runtime {}", nextRuntime);
        executor.scheduleAtFixedRate(this::processAll, nextRuntime, Duration.ofDays(1));
    }

    @Override
    protected void serviceStop() throws Exception {
        executor.shutdownNow();
        executor.awaitTermination(30, TimeUnit.SECONDS);
    }

    @Extern
    private void processAll() {
        synchronized (this) {
            if (!config.enabled()) {
                return;
            }
            log.info("Starting weekly digest");

            // Prepare for this run
            DigestRun digestRun = new DigestRun();

            // Check if this run is already complete or already in progress
            if (!lock(digestRun)) {
                return;
            }

            // Iterate all accounts
            long countFailed = 0;
            long countSent = 0;
            long countSkipped = 0;
            Optional<String> cursorOpt = Optional.empty();
            do {
                SearchAccountsResponse searchAccountsResponse = accountStore.searchAccounts(AccountSearchSuperAdmin.builder()
                        .filterStatus(ImmutableList.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVETRIAL))
                        // Pre-filtering by planid, will be checked again below
                        .filterPlanid(CommonPlanVerifyStore.PLANS_WITHOUT_WEEKLY_DIGEST.asList())
                        .invertPlanid(true)
                        .build(), true, cursorOpt, Optional.empty());
                cursorOpt = searchAccountsResponse.getCursorOpt();
                for (Account account : searchAccountsResponse.getAccounts()) {
                    // Process each account individually
                    try {
                        boolean isSent = processAccount(digestRun, account);
                        if (isSent) {
                            countSent++;
                        } else {
                            countSkipped++;
                        }
                    } catch (Exception ex) {
                        countFailed++;
                        log.warn("Weekly digest: Failed to process account {} {}",
                                account.getEmail(), account.getAccountId(), ex);
                    }
                }
            } while (cursorOpt.isPresent());

            // Signal completion
            complete(digestRun);
            log.info("Weekly digest: Complete, {} sent {} skipped {} failed",
                    countSent, countSkipped, countFailed);
        }
    }

    @Extern
    private boolean processAccount(String accountId) {
        return processAccount(
                new DigestRun(),
                accountStore.getAccount(accountId, true).orElseThrow());
    }

    @Extern
    private boolean processAccountWithCustomRange(String accountId, String start, String end) {
        return processAccount(
                new DigestRun(Instant.parse(start), Instant.parse(end)),
                accountStore.getAccount(accountId, true).orElseThrow());
    }

    @SneakyThrows
    private boolean processAccount(DigestRun digestRun, Account account) {
        ImmutableList<DigestProject> projects = Stream.concat(account.getProjectIds().stream(), account.getExternalProjectIds().stream())
                .distinct()
                .filter(projectId -> planVerifyStore.verifyAccountAllowedDigest(account, projectId))
                .flatMap(projectId -> {
                    try {
                        return digestRun.projectCache.get(projectId).stream();
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                })
                .flatMap(project -> processAccountProject(digestRun, account, project).stream())
                .collect(ImmutableList.toImmutableList());
        if (projects.isEmpty()) {
            log.info("Weekly digest: skipping account {} {}",
                    account.getEmail(), account.getAccountId());
            return false;
        }
        String from = digestRun.getStart().atZone(ZoneId.of(configApp.zoneId()))
                .format(DateTimeFormatter.ofPattern("MMM d"));
        String to = digestRun.getEnd().atZone(ZoneId.of(configApp.zoneId()))
                .format(DateTimeFormatter.ofPattern("MMM d"));

        log.info("Weekly digest: sending to account {} {} projects {}",
                account.getEmail(), account.getAccountId(),
                projects.stream().map(DigestProject::getName).toArray());
        digestRun.rateLimiter.acquire();
        notificationService.onDigest(account, new Digest(from, to, projects));

        return true;
    }

    private Optional<DigestProject> processAccountProject(DigestRun digestRun, Account account, Project project) {
        UserModel adminUser = userStore.accountCreateOrGet(project.getProjectId(), account);

        // New posts
        Optional<DigestSection> newPostsSectionOpt = project.getVersionedConfigAdmin().getConfig().getContent().getCategories().stream()
                .map(Category::getCategoryId)
                .filter(categoryId -> categoryId.startsWith("feedback-"))
                .findAny()
                .map(categoryId -> processSectionPosts(digestRun, account, project,
                        "New feedback", IdeaSearchAdmin.builder()
                                .filterCreatedStart(digestRun.start)
                                .filterCreatedEnd(digestRun.end)
                                .filterCategoryIds(List.of(categoryId))
                                .filterAuthorId(adminUser.getUserId())
                                .invertAuthorId(true)
                                .sortBy(IdeaSearchAdmin.SortByEnum.NEW).build()))
                .orElseGet(() -> processSectionPosts(digestRun, account, project,
                        "New posts", IdeaSearchAdmin.builder()
                                .filterCreatedStart(digestRun.start)
                                .filterCreatedEnd(digestRun.end)
                                .filterAuthorId(adminUser.getUserId())
                                .invertAuthorId(true)
                                .sortBy(IdeaSearchAdmin.SortByEnum.NEW).build()));

        // Notifications
        Optional<DigestSection> notificationsSectionOpt = processSectionNotifications(digestRun, account, project, adminUser);

        // New users
        Optional<DigestSection> newUsersSectionOpt = processSectionUsers(digestRun, account, project, "New users", UserSearchAdmin.builder()
                        // Sort by newest users
                        .sortOrder(UserSearchAdmin.SortOrderEnum.DESC)
                        .sortBy(UserSearchAdmin.SortByEnum.CREATED).build(),
                // Filter out self
                user -> !user.getUserId().equals(adminUser.getUserId()));

        // See if we have enough data to send a digest
        if (notificationsSectionOpt.isEmpty() && newPostsSectionOpt.isEmpty()) {
            log.debug("No significant sections are present for account {} project {}", account.getAccountId(), project.getProjectId());
            return Optional.empty();
        }

        return Optional.of(new DigestProject(
                adminUser,
                project.getName(),
                project.getLink(),
                Stream.of(
                                notificationsSectionOpt,
                                newPostsSectionOpt,
                                newUsersSectionOpt)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(ImmutableList.toImmutableList())));
    }

    private Optional<DigestSection> processSectionPosts(DigestRun digestRun, Account account, Project project, String title, IdeaSearchAdmin search) {
        ImmutableList<String> ideaIds = ideaStore.searchIdeas(project.getProjectId(), search, false, Optional.empty())
                .getIdeaIds();
        ImmutableList<IdeaModel> ideas = ideaStore.getIdeas(project.getProjectId(), ideaIds).values()
                .stream()
                .filter(idea -> idea.getCreated().isAfter(digestRun.getStart())
                        && idea.getCreated().isBefore(digestRun.getEnd()))
                .collect(ImmutableList.toImmutableList());
        if (ideas.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DigestSection(
                title,
                ideas.stream()
                        .map(idea -> new DigestItem(
                                idea.getTitle(),
                                "https://" + Project.getHostname(project.getVersionedConfigAdmin().getConfig(), configApp) + "/post/" + idea.getIdeaId()))
                        .collect(ImmutableList.toImmutableList())));
    }

    private Optional<DigestSection> processSectionUsers(DigestRun digestRun, Account account, Project project, String title, UserSearchAdmin search, Predicate<UserModel> userPredicate) {
        ImmutableList<String> userIds = userStore.searchUsers(project.getProjectId(), search, false, Optional.empty(), Optional.empty())
                .getUserIds();
        ImmutableList<UserModel> users = userStore.getUsers(project.getProjectId(), userIds).values()
                .stream()
                .filter(userPredicate)
                // Filter out digest range
                .filter(user -> user.getCreated().isAfter(digestRun.getStart())
                        && user.getCreated().isBefore(digestRun.getEnd()))
                .collect(ImmutableList.toImmutableList());
        if (users.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DigestSection(
                title,
                users.stream()
                        .map(user -> new DigestItem(
                                user.getNameOrEmailOrId(),
                                "https://" + Project.getHostname(project.getVersionedConfigAdmin().getConfig(), configApp) + "/user/" + user.getUserId()))
                        .collect(ImmutableList.toImmutableList())));
    }

    private Optional<DigestSection> processSectionNotifications(DigestRun digestRun, Account account, Project project, UserModel adminUser) {
        ImmutableList<NotificationModel> notifications = notificationStore.notificationList(
                        project.getProjectId(),
                        adminUser.getUserId(),
                        Optional.empty())
                .getNotifications()
                .stream()
                .filter(notification -> notification.getCreated().isAfter(digestRun.getStart())
                        && notification.getCreated().isBefore(digestRun.getEnd()))
                .collect(ImmutableList.toImmutableList());
        if (notifications.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DigestSection(
                "Missed notifications",
                notifications.stream()
                        .map(notification -> new DigestItem(
                                notification.getDescription(),
                                "https://" + Project.getHostname(project.getVersionedConfigAdmin().getConfig(), configApp) + notification.getLinkPath()))
                        .collect(ImmutableList.toImmutableList())));
    }

    @Extern
    private String checkLockNowExtern() {
        return checkLock(Instant.now()).toString();
    }

    @Extern
    private String checkLockExtern(String tsStr) {
        return checkLock(Instant.parse(tsStr)).toString();
    }

    @VisibleForTesting
    Optional<WeeklyDigestWork> checkLock(Instant now) {
        DigestRun digestRun = new DigestRun(ZonedDateTime.ofInstant(now, ZoneId.of(configApp.zoneId())));
        return Optional.ofNullable(weeklyDigestWorkSchema.fromItem(weeklyDigestWorkSchema.table().getItem(new GetItemSpec()
                .withPrimaryKey(weeklyDigestWorkSchema.primaryKey(Map.of(
                        "weekStart", digestRun.getStart()))
                ))));
    }

    @VisibleForTesting
    boolean lock(Instant now) {
        DigestRun digestRun = new DigestRun(ZonedDateTime.ofInstant(now, ZoneId.of(configApp.zoneId())));
        return lock(digestRun);
    }

    private boolean lock(DigestRun digestRun) {
        Expression expression = weeklyDigestWorkSchema.expressionBuilder()
                .conditionNotExists()
                .build();
        try {
            weeklyDigestWorkSchema.table().putItem(new PutItemSpec()
                    .withConditionExpression(expression.conditionExpression().orElse(null))
                    .withNameMap(expression.nameMap().orElse(null))
                    .withValueMap(expression.valMap().orElse(null))
                    .withItem(weeklyDigestWorkSchema.toItem(WeeklyDigestWork.builder()
                            .weekStart(digestRun.getStart())
                            .status(Status.PROCESSING)
                            .ttlInEpochSec(Instant.now().plus(3, ChronoUnit.HOURS).getEpochSecond())
                            .build())));
        } catch (ConditionalCheckFailedException ex) {
            return false;
        }
        return true;
    }

    @Extern
    private void skipThisWeek() {
        complete(new DigestRun());
    }

    @VisibleForTesting
    void complete(Instant now) {
        DigestRun digestRun = new DigestRun(ZonedDateTime.ofInstant(now, ZoneId.of(configApp.zoneId())));
        complete(digestRun);
    }

    private void complete(DigestRun digestRun) {
        weeklyDigestWorkSchema.table().putItem(new PutItemSpec()
                .withItem(weeklyDigestWorkSchema.toItem(WeeklyDigestWork.builder()
                        .weekStart(digestRun.getStart())
                        .status(Status.COMPLETE)
                        .ttlInEpochSec(Instant.now().plus(30, ChronoUnit.DAYS).getEpochSecond())
                        .build())));
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(ZoneId.of(configApp.zoneId()));
    }

    public static Duration getNextRuntime(ZonedDateTime now, int sendAtTime, int jitterSeconds) {
        ZonedDateTime next = now.withHour(sendAtTime)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        if (next.isBefore(now)) {
            next = next.plusDays(1);
        }
        Duration between = Duration.between(now, next);

        // Add jitter
        if (jitterSeconds > 0) {
            between = between.plus(Duration.ofSeconds(ThreadLocalRandom.current().nextInt(jitterSeconds) - jitterSeconds / 2));
        }

        return between;
    }

    @VisibleForTesting
    static Instant getDigestStart(ZonedDateTime now) {
        ZonedDateTime lastWeek = now.minusWeeks(1);
        // get Monday
        return lastWeek.minusDays(lastWeek.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue())
                .withHour(0).withMinute(0).withSecond(0).withNano(0).toInstant();
    }

    @VisibleForTesting
    static Instant getDigestEnd(ZonedDateTime now) {
        ZonedDateTime lastWeek = now.minusWeeks(1);
        // get Monday
        return lastWeek.plusDays(DayOfWeek.SUNDAY.getValue() - lastWeek.getDayOfWeek().getValue())
                .withHour(23).withMinute(59).withSecond(59).withNano(999999999).toInstant();
    }

    public static Module module() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(WeeklyDigestService.class).asEagerSingleton();
                Multibinder.newSetBinder(binder(), ManagedService.class).addBinding().to(WeeklyDigestService.class).asEagerSingleton();
                install(ConfigSystem.configModule(Config.class));
            }
        };
    }
}
