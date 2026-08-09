package com.sheyito.economicmaster;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vanilla's own registries (Items, Blocks, BuiltInRegistries, ...) are populated by
 * {@link Bootstrap#bootStrap()}, which normally only ever runs once as part of launching the
 * game or a dedicated server. Tests that touch any of that (ItemStack, item registry lookups,
 * SimpleContainer with real items, ...) need to trigger it themselves first.
 * <p>
 * NeoForge patches vanilla's {@code Blocks} class init to read per-mod feature flags via
 * {@code net.neoforged.fml.loading.LoadingModList.get()}, which is otherwise only ever
 * populated by FML's real mod-loading process - never in a plain JUnit JVM. Without a stand-in,
 * {@code Bootstrap.bootStrap()} fails with a NullPointerException before any registry finishes
 * populating. This installs an empty (no mods) {@code LoadingModList} via reflection first, so
 * that code path is a no-op instead of a crash. This is test-only scaffolding, isolated from
 * production code entirely, and mirrors a well-established pattern other NeoForge mod test
 * suites use for headless registry bootstrap.
 */
public final class TestBootstrap {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private TestBootstrap() {
    }

    public static void ensure() {
        if (DONE.compareAndSet(false, true)) {
            installEmptyLoadingModList();
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        }
    }

    private static void installEmptyLoadingModList() {
        try {
            Class<?> loadingModListClass = Class.forName("net.neoforged.fml.loading.LoadingModList");
            Constructor<?> constructor = loadingModListClass.getDeclaredConstructor(List.class, List.class, List.class, Map.class);
            constructor.setAccessible(true);
            Object emptyModList = constructor.newInstance(List.of(), List.of(), List.of(), Map.of());

            Field instanceField = loadingModListClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            instanceField.set(null, emptyModList);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not stub LoadingModList for headless test bootstrap - "
                    + "this NeoForge internal may have changed shape.", e);
        }
    }
}
