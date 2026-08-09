/* Copyright 2018 The Chromium Authors. BSD-style license. */
package org.chromium.support_lib_boundary.util;

import android.os.Build;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;

/** Focused copy of Chromium's support-library boundary reflection utility. */
public final class BoundaryInterfaceReflectionUtil {
    private BoundaryInterfaceReflectionUtil() {}

    public static <T> T castToSuppLibClass(Class<T> type, InvocationHandler handler) {
        if (handler == null) return null;
        Object proxy = Proxy.newProxyInstance(
                BoundaryInterfaceReflectionUtil.class.getClassLoader(),
                new Class<?>[] { type }, handler);
        return type.cast(proxy);
    }

    public static InvocationHandler createInvocationHandlerFor(Object delegate) {
        return delegate == null ? null : new InvocationHandlerWithDelegateGetter(delegate);
    }

    public static Object getDelegateFromInvocationHandler(InvocationHandler handler) {
        if (handler instanceof InvocationHandlerWithDelegateGetter) {
            return ((InvocationHandlerWithDelegateGetter) handler).delegate;
        }
        return null;
    }

    public static boolean containsFeature(Collection<String> features, String soughtFeature) {
        if (features == null || soughtFeature == null || soughtFeature.endsWith(":dev")) {
            return false;
        }
        if (features.contains(soughtFeature)) return true;
        return isDebuggable() && features.contains(soughtFeature + ":dev");
    }

    private static boolean isDebuggable() {
        return "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
    }

    /** Invocation handler used when an app-side boundary implementation crosses class loaders. */
    public static final class InvocationHandlerWithDelegateGetter implements InvocationHandler {
        final Object delegate;

        InvocationHandlerWithDelegateGetter(Object delegate) {
            this.delegate = delegate;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("toString".equals(name) && method.getParameterTypes().length == 0) {
                return delegate.toString();
            }
            if ("hashCode".equals(name) && method.getParameterTypes().length == 0) {
                return delegate.hashCode();
            }
            if ("equals".equals(name) && method.getParameterTypes().length == 1) {
                return proxy == (args == null ? null : args[0]);
            }
            Method target = delegate.getClass().getMethod(name, method.getParameterTypes());
            target.setAccessible(true);
            return target.invoke(delegate, args);
        }
    }
}
