package com.npcmaker.platform;

import com.npcmaker.Constants;
import com.npcmaker.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

// Loader-specific implementations are found through META-INF/services files in each loader project.
public final class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
