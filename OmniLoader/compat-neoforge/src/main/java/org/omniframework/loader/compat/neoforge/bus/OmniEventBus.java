package org.omniframework.loader.compat.neoforge.bus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;

/** In-process event bus used by the hosted NeoForge layer. */
public final class OmniEventBus implements IEventBus {

    private final Map<Class<?>, List<Consumer<Object>>> listeners = new ConcurrentHashMap<>();

    @Override
    public void register(Object target) {
        Objects.requireNonNull(target, "target");
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(SubscribeEvent.class)) {
                continue;
            }
            if (method.getParameterCount() != 1) {
                throw new IllegalArgumentException(
                        "@SubscribeEvent method must take exactly one parameter: " + method);
            }
            Class<?> eventType = method.getParameterTypes()[0];
            method.setAccessible(true);
            addListener(eventType, event -> {
                try {
                    method.invoke(target, event);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "event listener failed: " + method + " on " + eventType.getName(), e);
                }
            });
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void addListener(Class<T> eventType, Consumer<T> listener) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        listeners
                .computeIfAbsent(eventType, key -> new ArrayList<>())
                .add(event -> listener.accept((T) event));
    }

    @Override
    public <T> T post(T event) {
        Objects.requireNonNull(event, "event");
        Class<?> type = event.getClass();
        // Exact type then superclasses / interfaces (shallow hierarchy walk).
        for (Class<?> cursor = type; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            dispatch(cursor, event);
        }
        for (Class<?> iface : type.getInterfaces()) {
            dispatch(iface, event);
        }
        return event;
    }

    private void dispatch(Class<?> type, Object event) {
        List<Consumer<Object>> list = listeners.get(type);
        if (list == null) {
            return;
        }
        for (Consumer<Object> listener : List.copyOf(list)) {
            listener.accept(event);
        }
    }
}
