package com.smotana.clearflask.web.filter;

import com.google.common.base.Strings;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;
import com.kik.config.ice.ConfigSystem;
import com.kik.config.ice.annotations.DefaultValue;
import com.smotana.clearflask.core.ManagedService;

import java.util.Optional;

public class UmbrellaFilterProvider extends ManagedService {

    public interface Config {
        @DefaultValue("false")
        boolean enabled();

        @DefaultValue("dataspray")
        String organizationName();

        @DefaultValue("")
        String apiKey();

        @DefaultValue("")
        String endpointUrl();
    }

    @Inject
    private Config config;

    @Override
    protected void serviceStart() throws Exception {
        System.setProperty("umbrella.enabled", Boolean.toString(config.enabled()));
        Optional.ofNullable(Strings.emptyToNull(config.organizationName())).ifPresent(org -> System.setProperty("umbrella.org", org));
        Optional.ofNullable(Strings.emptyToNull(config.apiKey())).ifPresent(apiKey -> System.setProperty("umbrella.api.key", apiKey));
        Optional.ofNullable(Strings.emptyToNull(config.endpointUrl())).ifPresent(endpointUrl -> System.setProperty("umbrella.endpoint.url", endpointUrl));
    }

    public static Module module() {
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(UmbrellaFilterProvider.class).asEagerSingleton();
                install(ConfigSystem.configModule(Config.class));
                Multibinder.newSetBinder(binder(), ManagedService.class).addBinding().to(UmbrellaFilterProvider.class).asEagerSingleton();
            }
        };
    }
}
