/*
 * Copyright 2008-2016 the original author or authors.
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
package org.codehaus.griffon.runtime.core.mvc;

import griffon.core.ApplicationClassLoader;
import griffon.core.ApplicationEvent;
import griffon.core.GriffonApplication;
import griffon.core.artifact.ArtifactManager;
import griffon.core.artifact.GriffonArtifact;
import griffon.core.artifact.GriffonClass;
import griffon.core.artifact.GriffonController;
import griffon.core.artifact.GriffonMvcArtifact;
import griffon.core.artifact.GriffonView;
import griffon.core.mvc.MVCGroup;
import griffon.core.mvc.MVCGroupConfiguration;
import griffon.exceptions.FieldException;
import griffon.exceptions.GriffonException;
import griffon.exceptions.MVCGroupInstantiationException;
import griffon.exceptions.NewInstanceException;
import griffon.exceptions.PropertyException;
import griffon.inject.Contextual;
import griffon.util.CollectionUtils;
import org.codehaus.griffon.runtime.core.injection.InjectionUnitOfWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static griffon.core.GriffonExceptionHandler.sanitize;
import static griffon.util.AnnotationUtils.annotationsOfMethodParameter;
import static griffon.util.AnnotationUtils.findAnnotation;
import static griffon.util.AnnotationUtils.nameFor;
import static griffon.util.AnnotationUtils.namesFor;
import static griffon.util.ConfigUtils.getConfigValueAsBoolean;
import static griffon.util.GriffonClassUtils.getAllDeclaredFields;
import static griffon.util.GriffonClassUtils.getPropertyDescriptors;
import static griffon.util.GriffonClassUtils.setFieldValue;
import static griffon.util.GriffonClassUtils.setPropertyOrFieldValueNoException;
import static griffon.util.GriffonClassUtils.setPropertyValue;
import static griffon.util.GriffonNameUtils.capitalize;
import static griffon.util.GriffonNameUtils.isBlank;
import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

/**
 * Base implementation of the {@code MVCGroupManager} interface.
 *
 * @author Andres Almiray
 * @since 2.0.0
 */
public class DefaultMVCGroupManager extends AbstractMVCGroupManager {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultMVCGroupManager.class);
    private static final String CONFIG_KEY_COMPONENT = "component";
    private static final String CONFIG_KEY_EVENTS_LIFECYCLE = "events.lifecycle";
    private static final String CONFIG_KEY_EVENTS_INSTANTIATION = "events.instantiation";
    private static final String CONFIG_KEY_EVENTS_DESTRUCTION = "events.destruction";
    private static final String CONFIG_KEY_EVENTS_LISTENER = "events.listener";
    private static final String KEY_PARENT_GROUP = "parentGroup";

    private final ApplicationClassLoader applicationClassLoader;

    @Inject
    public DefaultMVCGroupManager(@Nonnull GriffonApplication application, @Nonnull ApplicationClassLoader applicationClassLoader) {
        super(application);
        this.applicationClassLoader = requireNonNull(applicationClassLoader, "Argument 'applicationClassLoader' must not be null");
    }

    protected void doInitialize(@Nonnull Map<String, MVCGroupConfiguration> configurations) {
        requireNonNull(configurations, "Argument 'configurations' must not be null");
        for (MVCGroupConfiguration configuration : configurations.values()) {
            addConfiguration(configuration);
        }
    }

    @Nonnull
    protected MVCGroup createMVCGroup(@Nonnull MVCGroupConfiguration configuration, @Nullable String mvcId, @Nonnull Map<String, Object> args) {
        requireNonNull(configuration, ERROR_CONFIGURATION_NULL);
        requireNonNull(args, ERROR_ARGS_NULL);

        mvcId = resolveMvcId(configuration, mvcId);
        checkIdIsUnique(mvcId, configuration);

        LOG.debug("Building MVC group '{}' with name '{}'", configuration.getMvcType(), mvcId);
        Map<String, Object> argsCopy = copyAndConfigureArguments(args, configuration, mvcId);

        // figure out what the classes are
        Map<String, ClassHolder> classMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> memberEntry : configuration.getMembers().entrySet()) {
            String memberType = memberEntry.getKey();
            String memberClassName = memberEntry.getValue();
            selectClassesPerMember(memberType, memberClassName, classMap);
        }

        boolean isEventPublishingEnabled = getApplication().getEventRouter().isEventPublishingEnabled();
        getApplication().getEventRouter().setEventPublishingEnabled(isConfigFlagEnabled(configuration, CONFIG_KEY_EVENTS_INSTANTIATION));
        Map<String, Object> instances = new LinkedHashMap<>();
        List<Object> injectedInstances = new ArrayList<>();

        try {
            InjectionUnitOfWork.start();
        } catch (IllegalStateException ise) {
            throw new MVCGroupInstantiationException("Can not instantiate MVC group '" + configuration.getMvcType() + "' with id '" + mvcId + "'", configuration.getMvcType(), mvcId, ise);
        }

        try {
            instances.putAll(instantiateMembers(classMap, argsCopy));
        } finally {
            getApplication().getEventRouter().setEventPublishingEnabled(isEventPublishingEnabled);
            try {
                injectedInstances.addAll(InjectionUnitOfWork.finish());
            } catch (IllegalStateException ise) {
                throw new MVCGroupInstantiationException("Can not instantiate MVC group '" + configuration.getMvcType() + "' with id '" + mvcId + "'", configuration.getMvcType(), mvcId, ise);
            }
        }

        MVCGroup group = newMVCGroup(configuration, mvcId, instances, (MVCGroup) args.get(KEY_PARENT_GROUP));
        adjustMvcArguments(group, argsCopy);

        boolean fireEvents = isConfigFlagEnabled(configuration, CONFIG_KEY_EVENTS_LIFECYCLE);
        if (fireEvents) {
            getApplication().getEventRouter().publishEvent(ApplicationEvent.INITIALIZE_MVC_GROUP.getName(), asList(configuration, group));
        }

        // special case -- controllers are added as application listeners
        if (isConfigFlagEnabled(group.getConfiguration(), CONFIG_KEY_EVENTS_LISTENER)) {
            GriffonController controller = group.getController();
            if (controller != null) {
                getApplication().getEventRouter().addEventListener(controller);
            }
        }

        // mutually set each other to the available fields and inject args
        fillReferencedProperties(group, argsCopy);

        doAddGroup(group);

        initializeMembers(group, argsCopy);
        if (group instanceof AbstractMVCGroup) {
            ((AbstractMVCGroup) group).getInjectedInstances().addAll(injectedInstances);
        }

        if (fireEvents) {
            getApplication().getEventRouter().publishEvent(ApplicationEvent.CREATE_MVC_GROUP.getName(), asList(group));
        }

        return group;
    }

    protected void adjustMvcArguments(@Nonnull MVCGroup group, @Nonnull Map<String, Object> args) {
        // must set it again because mvcId might have been initialized internally
        args.put("mvcId", group.getMvcId());
        args.put("mvcGroup", group);
        args.put("application", getApplication());
    }

    @Nonnull
    @SuppressWarnings("ConstantConditions")
    protected String resolveMvcId(@Nonnull MVCGroupConfiguration configuration, @Nullable String mvcId) {
        boolean component = getConfigValueAsBoolean(configuration.getConfig(), CONFIG_KEY_COMPONENT, false);

        if (isBlank(mvcId)) {
            if (component) {
                mvcId = configuration.getMvcType() + "-" + System.nanoTime();
            } else {
                mvcId = configuration.getMvcType();
            }
        }
        return mvcId;
    }

    @SuppressWarnings("unchecked")
    protected void selectClassesPerMember(@Nonnull String memberType, @Nonnull String memberClassName, @Nonnull Map<String, ClassHolder> classMap) {
        GriffonClass griffonClass = getApplication().getArtifactManager().findGriffonClass(memberClassName);
        ClassHolder classHolder = new ClassHolder();
        if (griffonClass != null) {
            classHolder.artifactClass = (Class<? extends GriffonArtifact>) griffonClass.getClazz();
        } else {
            classHolder.regularClass = loadClass(memberClassName);
        }
        classMap.put(memberType, classHolder);
    }

    @Nonnull
    protected Map<String, Object> copyAndConfigureArguments(@Nonnull Map<String, Object> args, @Nonnull MVCGroupConfiguration configuration, @Nonnull String mvcId) {
        Map<String, Object> argsCopy = CollectionUtils.<String, Object>map()
            .e("application", getApplication())
            .e("mvcType", configuration.getMvcType())
            .e("mvcId", mvcId)
            .e("configuration", configuration);

        if (args.containsKey(KEY_PARENT_GROUP)) {
            if (args.get(KEY_PARENT_GROUP) instanceof MVCGroup) {
                MVCGroup parentGroup = (MVCGroup) args.get(KEY_PARENT_GROUP);
                for (Map.Entry<String, Object> e : parentGroup.getMembers().entrySet()) {
                    args.put("parent" + capitalize(e.getKey()), e.getValue());
                }
            }
        }

        argsCopy.putAll(args);
        return argsCopy;
    }

    protected void checkIdIsUnique(@Nonnull String mvcId, @Nonnull MVCGroupConfiguration configuration) {
        if (findGroup(mvcId) != null) {
            String action = getApplication().getConfiguration().getAsString("griffon.mvcid.collision", "exception");
            if ("warning".equalsIgnoreCase(action)) {
                LOG.warn("A previous instance of MVC group '{}' with id '{}' exists. Destroying the old instance first.", configuration.getMvcType(), mvcId);
                destroyMVCGroup(mvcId);
            } else {
                throw new MVCGroupInstantiationException("Can not instantiate MVC group '" + configuration.getMvcType() + "' with id '" + mvcId + "' because a previous instance with that name exists and was not disposed off properly.", configuration.getMvcType(), mvcId);
            }
        }
    }

    @Nonnull
    protected Map<String, Object> instantiateMembers(@Nonnull Map<String, ClassHolder> classMap, @Nonnull Map<String, Object> args) {
        // instantiate the parts
        Map<String, Object> instanceMap = new LinkedHashMap<>();
        for (Map.Entry<String, ClassHolder> classEntry : classMap.entrySet()) {
            String memberType = classEntry.getKey();
            if (args.containsKey(memberType)) {
                // use provided value, even if null
                instanceMap.put(memberType, args.get(memberType));
            } else {
                // otherwise create a new value
                ClassHolder classHolder = classEntry.getValue();
                if (classHolder.artifactClass != null) {
                    Class<? extends GriffonArtifact> memberClass = classHolder.artifactClass;
                    ArtifactManager artifactManager = getApplication().getArtifactManager();
                    GriffonClass griffonClass = artifactManager.findGriffonClass(memberClass);
                    GriffonArtifact instance = artifactManager.newInstance(griffonClass);
                    instanceMap.put(memberType, instance);
                    args.put(memberType, instance);
                } else {
                    Class<?> memberClass = classHolder.regularClass;
                    try {
                        Object instance = memberClass.newInstance();
                        getApplication().getInjector().injectMembers(instance);
                        instanceMap.put(memberType, instance);
                        args.put(memberType, instance);
                    } catch (InstantiationException | IllegalAccessException e) {
                        LOG.error("Can't create member {} with {}", memberType, memberClass);
                        throw new NewInstanceException(memberClass, e);
                    }
                }
            }
        }
        return instanceMap;
    }

    protected void initializeMembers(@Nonnull MVCGroup group, @Nonnull Map<String, Object> args) {
        LOG.debug("Initializing each MVC member of group '{}'", group.getMvcId());
        for (Map.Entry<String, Object> memberEntry : group.getMembers().entrySet()) {
            String memberType = memberEntry.getKey();
            Object member = memberEntry.getValue();
            if (member instanceof GriffonArtifact) {
                initializeArtifactMember(group, memberType, (GriffonArtifact) member, args);
            } else {
                initializeNonArtifactMember(group, memberType, member, args);
            }
        }
    }

    protected void initializeArtifactMember(@Nonnull MVCGroup group, @Nonnull String type, final @Nonnull GriffonArtifact member, final @Nonnull Map<String, Object> args) {
        if (member instanceof GriffonView) {
            getApplication().getUIThreadManager().runInsideUISync(new Runnable() {
                @Override
                public void run() {
                    try {
                        GriffonView view = (GriffonView) member;
                        view.initUI();
                        view.mvcGroupInit(args);
                    } catch (RuntimeException e) {
                        throw (RuntimeException) sanitize(e);
                    }
                }
            });
        } else if (member instanceof GriffonMvcArtifact) {
            ((GriffonMvcArtifact) member).mvcGroupInit(args);
        }
    }

    protected void initializeNonArtifactMember(@Nonnull MVCGroup group, @Nonnull String type, @Nonnull Object member, @Nonnull Map<String, Object> args) {
        // empty
    }

    protected void fillReferencedProperties(@Nonnull MVCGroup group, @Nonnull Map<String, Object> args) {
        for (Map.Entry<String, Object> memberEntry : group.getMembers().entrySet()) {
            String memberType = memberEntry.getKey();
            Object member = memberEntry.getValue();
            if (member instanceof GriffonArtifact) {
                fillArtifactMemberProperties(group, memberType, (GriffonArtifact) member, args);
            } else {
                fillNonArtifactMemberProperties(group, memberType, member, args);
            }
            fillContextualMemberProperties(group, memberType, member);
        }
    }

    protected void fillArtifactMemberProperties(@Nonnull MVCGroup group, @Nonnull String memberType, @Nonnull GriffonArtifact member, @Nonnull Map<String, Object> args) {
        Collection<String> alreadySet = new ArrayList<>();

        for (PropertyDescriptor descriptor : getPropertyDescriptors(member.getClass())) {
            Method method = descriptor.getWriteMethod();
            if (method != null && method.getAnnotation(Contextual.class) == null) {
                String argName = descriptor.getName();
                Object argValue = args.get(argName);

                if (argValue == null) {
                    if (findAnnotation(annotationsOfMethodParameter(method, 0), Nonnull.class) != null) {
                        throw new IllegalStateException("Could not inject argument " + argName +
                            " on property '" + descriptor.getName() + "' in " + memberType + " (" + member.getClass().getName() +
                            "). Property does not accept null values.");
                    } else {
                        alreadySet.add(argName);
                        continue;
                    }
                }

                try {
                    setPropertyValue(member, argName, argValue);
                    alreadySet.add(argName);
                } catch (PropertyException x) {
                    throw new MVCGroupInstantiationException(group.getMvcType(), group.getMvcId(), x);
                }

            }
        }

        for (Field field : getAllDeclaredFields(member.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) { continue; }
            String argName = field.getName();
            if (alreadySet.contains(argName)) { continue; }
            Object argValue = args.get(argName);
            if (field.getType().isPrimitive() && argValue == null) { continue; }

            if (argValue == null) {
                if (field.getAnnotation(Nonnull.class) != null) {
                    throw new IllegalStateException("Could not inject argument " + argName +
                        " on field '" + field.getName() + "' in " + memberType + " (" + member.getClass().getName() +
                        "). Field does not accept null values.");
                } else {
                    continue;
                }
            }

            try {
                setFieldValue(member, argName, argValue);
            } catch (FieldException e) {
                throw new MVCGroupInstantiationException(group.getMvcType(), group.getMvcId(), e);
            }
        }
    }

    protected void fillNonArtifactMemberProperties(@Nonnull MVCGroup group, @Nonnull String memberType, @Nonnull Object member, @Nonnull Map<String, Object> args) {
        // empty
    }

    protected void fillContextualMemberProperties(@Nonnull MVCGroup group, @Nonnull String type, @Nonnull Object member) {
        for (PropertyDescriptor descriptor : getPropertyDescriptors(member.getClass())) {
            Method method = descriptor.getWriteMethod();
            if (method != null && method.getAnnotation(Contextual.class) != null) {
                String key = nameFor(method);
                Object arg = group.getContext().get(key);

                Nonnull nonNull = findAnnotation(annotationsOfMethodParameter(method, 0), Nonnull.class);
                if (arg == null && nonNull != null) {
                    throw new IllegalStateException("Could not find an instance of type " +
                        method.getParameterTypes()[0].getName() + " under key '" + key +
                        "' in the context of MVCGroup[" + group.getMvcType() + ":" + group.getMvcId() +
                        "] to be injected on property '" + descriptor.getName() +
                        "' in " + type + " (" + member.getClass().getName() + "). Property does not accept null values.");
                }

                try {
                    method.invoke(member, arg);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new MVCGroupInstantiationException(group.getMvcType(), group.getMvcId(), e);
                }
            }
        }

        for (Field field : getAllDeclaredFields(member.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) { continue; }
            if (field.getAnnotation(Contextual.class) != null) {
                Object value = null;
                String[] keys = namesFor(field);
                for (String key : keys) {
                    if (group.getContext().containsKey(key)) {
                        value = group.getContext().get(key);
                    }
                }

                if (value == null && field.getAnnotation(Nonnull.class) != null) {
                    throw new IllegalStateException("Could not find an instance of type " +
                        field.getType().getName() + " under keys '" + Arrays.toString(keys) +
                        "' in the context of MVCGroup[" + group.getMvcType() + ":" + group.getMvcId() +
                        "] to be injected on field '" + field.getName() +
                        "' in " + type + " (" + member.getClass().getName() + "). Field does not accept null values.");
                }

                try {
                    setFieldValue(member, field.getName(), value);
                } catch (FieldException e) {
                    throw new MVCGroupInstantiationException(group.getMvcType(), group.getMvcId(), e);
                }
            }
        }
    }

    protected void doAddGroup(@Nonnull MVCGroup group) {
        addGroup(group);
    }

    public void destroyMVCGroup(@Nonnull String mvcId) {
        MVCGroup group = findGroup(mvcId);
        LOG.debug("Group '{}' points to {}", mvcId, group);

        if (group == null) { return; }

        LOG.debug("Destroying MVC group identified by '{}'", mvcId);

        if (isConfigFlagEnabled(group.getConfiguration(), CONFIG_KEY_EVENTS_LISTENER)) {
            GriffonController controller = group.getController();
            if (controller != null) {
                getApplication().getEventRouter().removeEventListener(controller);
            }
        }

        boolean fireDestructionEvents = isConfigFlagEnabled(group.getConfiguration(), CONFIG_KEY_EVENTS_DESTRUCTION);

        destroyMembers(group, fireDestructionEvents);

        doRemoveGroup(group);
        group.destroy();

        if (isConfigFlagEnabled(group.getConfiguration(), CONFIG_KEY_EVENTS_LIFECYCLE)) {
            getApplication().getEventRouter().publishEvent(ApplicationEvent.DESTROY_MVC_GROUP.getName(), asList(group));
        }
    }

    protected void destroyMembers(@Nonnull MVCGroup group, boolean fireDestructionEvents) {
        for (Map.Entry<String, Object> memberEntry : group.getMembers().entrySet()) {
            Object member = memberEntry.getValue();
            if (member instanceof GriffonArtifact) {
                destroyArtifactMember(memberEntry.getKey(), (GriffonArtifact) member, fireDestructionEvents);
            } else {
                destroyNonArtifactMember(memberEntry.getKey(), member, fireDestructionEvents);
            }

        }

        if (group instanceof AbstractMVCGroup) {
            List<Object> injectedInstances = ((AbstractMVCGroup) group).getInjectedInstances();
            for (Object instance : injectedInstances) {
                getApplication().getInjector().release(instance);
            }
            injectedInstances.clear();
        }
    }

    protected void destroyArtifactMember(@Nonnull String type, @Nonnull GriffonArtifact member, boolean fireDestructionEvents) {
        if (member instanceof GriffonMvcArtifact) {
            final GriffonMvcArtifact artifact = (GriffonMvcArtifact) member;
            if (fireDestructionEvents) {
                getApplication().getEventRouter().publishEvent(ApplicationEvent.DESTROY_INSTANCE.getName(), asList(member.getClass(), artifact));
            }

            if (artifact instanceof GriffonView) {
                getApplication().getUIThreadManager().runInsideUISync(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            artifact.mvcGroupDestroy();
                        } catch (RuntimeException e) {
                            throw (RuntimeException) sanitize(e);
                        }
                    }
                });
            } else {
                artifact.mvcGroupDestroy();
            }

            // clear all parent* references
            for (String parentMemberName : new String[]{"parentModel", "parentView", "parentController", "parentGroup"}) {
                setPropertyOrFieldValueNoException(member, parentMemberName, null);
            }
        }

        destroyContextualMemberProperties(type, member);
    }

    protected void destroyContextualMemberProperties(@Nonnull String type, @Nonnull GriffonArtifact member) {
        for (Field field : getAllDeclaredFields(member.getClass())) {
            if (field.getAnnotation(Contextual.class) != null) {
                try {
                    setFieldValue(member, field.getName(), null);
                } catch (FieldException e) {
                    throw new IllegalStateException("Could not nullify field " +
                        field.getName() + "' in " + type + " (" + member.getClass().getName() + ")", e);
                }
            }
        }
    }

    protected void destroyNonArtifactMember(@Nonnull String type, @Nonnull Object member, boolean fireDestructionEvents) {
        // empty
    }

    protected void doRemoveGroup(@Nonnull MVCGroup group) {
        removeGroup(group);
    }

    protected boolean isConfigFlagEnabled(@Nonnull MVCGroupConfiguration configuration, @Nonnull String key) {
        return getConfigValueAsBoolean(configuration.getConfig(), key, true);
    }

    @Nullable
    protected Class<?> loadClass(@Nonnull String className) {
        try {
            return applicationClassLoader.get().loadClass(className);
        } catch (ClassNotFoundException e) {
            // #39 do not ignore this CNFE
            throw new GriffonException(e.toString(), e);
        }
    }

    protected static final class ClassHolder {
        protected Class<?> regularClass;
        protected Class<? extends GriffonArtifact> artifactClass;
    }
}
