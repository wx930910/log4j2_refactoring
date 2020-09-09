/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.util;

import java.util.List;
import java.util.Stack;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <em>Consider this class private.</em> Determines the caller's class.
 */
public class StackLocator {

    private final static StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final static StackWalker stackWalker = StackWalker.getInstance();

    private final static StackLocator INSTANCE = new StackLocator();

    private final static ThreadLocal<String> FQCN = new ThreadLocal<>();
    private final static FqcnCallerLocator LOCATOR = new FqcnCallerLocator();

    public static StackLocator getInstance() {
        return INSTANCE;
    }

    private StackLocator() {
    }

    public Class<?> getCallerClass(final String fqcn) {
        return getCallerClass(fqcn, "");
    }

    public Class<?> getCallerClass(final String fqcn, final String pkg) {
        return walker.walk(s -> s.dropWhile(f -> !f.getClassName().equals(fqcn)).
                dropWhile(f -> f.getClassName().equals(fqcn)).dropWhile(f -> !f.getClassName().startsWith(pkg)).
                findFirst()).map(StackWalker.StackFrame::getDeclaringClass).orElse(null);
    }

    public Class<?> getCallerClass(final Class<?> anchor) {
        return walker.walk(s -> s.dropWhile(f -> !f.getDeclaringClass().equals(anchor)).
                dropWhile(f -> f.getDeclaringClass().equals(anchor)).findFirst()).
                map(StackWalker.StackFrame::getDeclaringClass).orElse(null);
    }

    public Class<?> getCallerClass(final int depth) {
        return walker.walk(s -> s.skip(depth).findFirst()).map(StackWalker.StackFrame::getDeclaringClass).orElse(null);
    }

    public Stack<Class<?>> getCurrentStackTrace() {
        // benchmarks show that using the SecurityManager is much faster than looping through getCallerClass(int)
        if (PrivateSecurityManagerStackTraceUtil.isEnabled()) {
            return PrivateSecurityManagerStackTraceUtil.getCurrentStackTrace();
        }
        Stack<Class<?>> stack = new Stack<Class<?>>();
        List<Class<?>> classes = walker.walk(s -> s.map(f -> f.getDeclaringClass()).collect(Collectors.toList()));
        stack.addAll(classes);
        return stack;
    }

    public StackTraceElement calcLocation(final String fqcnOfLogger) {
        FQCN.set(fqcnOfLogger);
        final StackWalker.StackFrame walk = walker.walk(LOCATOR);
        final StackTraceElement element = walk == null ? null : walk.toStackTraceElement();
        FQCN.set(null);
        return element;
    }

    public StackTraceElement getStackTraceElement(final int depth) {
        return stackWalker.walk(s -> s.skip(depth).findFirst()).get().toStackTraceElement();
    }

    static final class FqcnCallerLocator implements Function<Stream<StackWalker.StackFrame>, StackWalker.StackFrame> {

        @Override
        public StackWalker.StackFrame apply(Stream<StackWalker.StackFrame> stackFrameStream) {
            String fqcn = FQCN.get();
            boolean foundFqcn = false;
            Object[] frames = stackFrameStream.toArray();
            for (int i = 0; i < frames.length ; ++i) {
                final String className = ((StackWalker.StackFrame) frames[i]).getClassName();
                if (!foundFqcn) {
                    // Skip frames until we find the FQCN
                    foundFqcn = className.equals(fqcn);
                } else if (!className.equals(fqcn)) {
                    // The frame is no longer equal to the FQCN so it is the one we want.
                    return (StackWalker.StackFrame) frames[i];
                } // Otherwise it is equal to the FQCN so we need to skip it.
            }
            // Should never happen
            return null;
        }
    }
}
