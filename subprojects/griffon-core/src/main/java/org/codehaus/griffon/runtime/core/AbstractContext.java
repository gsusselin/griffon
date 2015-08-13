/*
 * Copyright 2008-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.griffon.runtime.core;

import griffon.core.Context;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author Andres Almiray
 * @since 2.2.0
 */
public abstract class AbstractContext implements Context {
    protected Context parentContext;

    public AbstractContext(@Nullable Context parentContext) {
        this.parentContext = parentContext;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(@Nonnull String key, @Nullable T defaultValue) {
        T value = (T) get(key);
        return value != null ? value : defaultValue;
    }

    @Nullable
    @Override
    public Object getAt(@Nonnull String key) {
        return get(key);
    }

    @Nullable
    @Override
    public <T> T getAt(@Nonnull String key, @Nullable T defaultValue) {
        return get(key, defaultValue);
    }

    @Nullable
    @Override
    public Object get(@Nonnull String key) {
        if (hasKey(key)) {
            return doGet(key);
        } else if (parentContext != null) {
            return parentContext.get(key);
        } else {
            return null;
        }
    }

    @Override
    public void destroy() {
        parentContext = null;
    }

    @Override
    public boolean containsKey(@Nonnull String key) {
        if (hasKey(key)) {
            return true;
        } else if (parentContext != null) {
            return parentContext.containsKey(key);
        }
        return false;
    }

    @Nullable
    protected abstract Object doGet(@Nonnull String key);
}
