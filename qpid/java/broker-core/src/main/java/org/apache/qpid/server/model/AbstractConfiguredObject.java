/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.model;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.AccessControlException;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import javax.security.auth.Subject;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.Module;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.module.SimpleModule;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.configuration.updater.Task;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.configuration.updater.TaskWithException;
import org.apache.qpid.server.configuration.updater.VoidTask;
import org.apache.qpid.server.configuration.updater.VoidTaskWithException;
import org.apache.qpid.server.security.SecurityManager;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;
import org.apache.qpid.server.store.ConfiguredObjectRecord;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.util.ServerScopedRuntimeException;
import org.apache.qpid.util.Strings;

public abstract class AbstractConfiguredObject<X extends ConfiguredObject<X>> implements ConfiguredObject<X>
{
    private static final Map<Class, Object> SECURE_VALUES;

    public static final String SECURED_STRING_VALUE = "********";

    static
    {
        Map<Class,Object> secureValues = new HashMap<Class, Object>();
        secureValues.put(String.class, SECURED_STRING_VALUE);
        secureValues.put(Integer.class, 0);
        secureValues.put(Long.class, 0l);
        secureValues.put(Byte.class, (byte)0);
        secureValues.put(Short.class, (short)0);
        secureValues.put(Double.class, (double)0);
        secureValues.put(Float.class, (float)0);

        SECURE_VALUES = Collections.unmodifiableMap(secureValues);
    }

    private enum DynamicState { UNINIT, OPENED, CLOSED };
    private final AtomicReference<DynamicState> _dynamicState = new AtomicReference<>(DynamicState.UNINIT);



    private final Map<String,Object> _attributes = new HashMap<String, Object>();
    private final Map<Class<? extends ConfiguredObject>, ConfiguredObject> _parents =
            new HashMap<Class<? extends ConfiguredObject>, ConfiguredObject>();
    private final Collection<ConfigurationChangeListener> _changeListeners =
            new ArrayList<ConfigurationChangeListener>();

    private final Map<Class<? extends ConfiguredObject>, Collection<ConfiguredObject<?>>> _children =
            new ConcurrentHashMap<Class<? extends ConfiguredObject>, Collection<ConfiguredObject<?>>>();
    private final Map<Class<? extends ConfiguredObject>, Map<UUID,ConfiguredObject<?>>> _childrenById =
            new ConcurrentHashMap<Class<? extends ConfiguredObject>, Map<UUID,ConfiguredObject<?>>>();
    private final Map<Class<? extends ConfiguredObject>, Map<String,ConfiguredObject<?>>> _childrenByName =
            new ConcurrentHashMap<Class<? extends ConfiguredObject>, Map<String,ConfiguredObject<?>>>();


    @ManagedAttributeField
    private final UUID _id;

    private final TaskExecutor _taskExecutor;

    private final Class<? extends ConfiguredObject> _category;
    private final Class<? extends ConfiguredObject> _bestFitInterface;
    private final Model _model;

    @ManagedAttributeField
    private long _createdTime;

    @ManagedAttributeField
    private String _createdBy;

    @ManagedAttributeField
    private long _lastUpdatedTime;

    @ManagedAttributeField
    private String _lastUpdatedBy;

    @ManagedAttributeField
    private String _name;

    @ManagedAttributeField
    private Map<String,String> _context;

    @ManagedAttributeField
    private boolean _durable;

    @ManagedAttributeField
    private String _description;

    @ManagedAttributeField
    private LifetimePolicy _lifetimePolicy;

    private final Map<String, ConfiguredObjectAttribute<?,?>> _attributeTypes;
    private final Map<String, ConfiguredObjectTypeRegistry.AutomatedField> _automatedFields;
    private final Map<State, Map<State, Method>> _stateChangeMethods;

    @ManagedAttributeField
    private String _type;

    private final OwnAttributeResolver _attributeResolver = new OwnAttributeResolver(this);

    @ManagedAttributeField( afterSet = "attainStateIfResolved" )
    private State _desiredState;
    private boolean _openComplete;

    protected static Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parentsMap(ConfiguredObject<?>... parents)
    {
        final Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parentsMap =
                new HashMap<Class<? extends ConfiguredObject>, ConfiguredObject<?>>();

        for(ConfiguredObject<?> parent : parents)
        {
            parentsMap.put(parent.getCategoryClass(), parent);
        }
        return parentsMap;
    }

    protected AbstractConfiguredObject(final Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parents,
                                       Map<String, Object> attributes)
    {
        this(parents, attributes, parents.values().iterator().next().getTaskExecutor());
    }


    protected AbstractConfiguredObject(final Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parents,
                                       Map<String, Object> attributes,
                                       TaskExecutor taskExecutor)
    {
        this(parents, attributes, taskExecutor, parents.values().iterator().next().getModel());
    }

    protected AbstractConfiguredObject(final Map<Class<? extends ConfiguredObject>, ConfiguredObject<?>> parents,
                                       Map<String, Object> attributes,
                                       TaskExecutor taskExecutor,
                                       Model model)
    {
        _taskExecutor = taskExecutor;
        if(taskExecutor == null)
        {
            throw new NullPointerException("task executor is null");
        }
        _model = model;

        _category = ConfiguredObjectTypeRegistry.getCategory(getClass());

        _attributeTypes = ConfiguredObjectTypeRegistry.getAttributeTypes(getClass());
        _automatedFields = ConfiguredObjectTypeRegistry.getAutomatedFields(getClass());
        _stateChangeMethods = ConfiguredObjectTypeRegistry.getStateChangeMethods(getClass());

        Object idObj = attributes.get(ID);

        UUID uuid;
        if(idObj == null)
        {
            uuid = UUID.randomUUID();
            attributes = new HashMap<String, Object>(attributes);
            attributes.put(ID, uuid);
        }
        else
        {
            uuid = AttributeValueConverter.UUID_CONVERTER.convert(idObj, this);
        }
        _id = uuid;
        _name = AttributeValueConverter.STRING_CONVERTER.convert(attributes.get(NAME),this);
        if(_name == null)
        {
            throw new IllegalArgumentException("The name attribute is mandatory for " + getClass().getSimpleName() + " creation.");
        }

        _type = ConfiguredObjectTypeRegistry.getType(getClass());
        _bestFitInterface = calculateBestFitInterface();

        if(attributes.get(TYPE) != null && !_type.equals(attributes.get(TYPE)))
        {
            throw new IllegalConfigurationException("Provided type is " + attributes.get(TYPE)
                                                    + " but calculated type is " + _type);
        }

        for (Class<? extends ConfiguredObject> childClass : getModel().getChildTypes(getCategoryClass()))
        {
            _children.put(childClass, new CopyOnWriteArrayList<ConfiguredObject<?>>());
            _childrenById.put(childClass, new ConcurrentHashMap<UUID, ConfiguredObject<?>>());
            _childrenByName.put(childClass, new ConcurrentHashMap<String, ConfiguredObject<?>>());
        }

        for(Map.Entry<Class<? extends ConfiguredObject>, ConfiguredObject<?>> entry : parents.entrySet())
        {
            addParent((Class<ConfiguredObject<?>>) entry.getKey(), entry.getValue());
        }

        Object durableObj = attributes.get(DURABLE);
        _durable = AttributeValueConverter.BOOLEAN_CONVERTER.convert(durableObj == null
                                                                             ? ((ConfiguredAutomatedAttribute) (_attributeTypes
                .get(DURABLE))).defaultValue()
                                                                             : durableObj, this);

        for (String name : getAttributeNames())
        {
            if (attributes.containsKey(name))
            {
                final Object value = attributes.get(name);
                if (value != null)
                {
                    _attributes.put(name, value);
                }
            }
        }

        if(!_attributes.containsKey(CREATED_BY))
        {
            final AuthenticatedPrincipal currentUser = SecurityManager.getCurrentUser();
            if(currentUser != null)
            {
                _attributes.put(CREATED_BY, currentUser.getName());
            }
        }
        if(!_attributes.containsKey(CREATED_TIME))
        {
            _attributes.put(CREATED_TIME, System.currentTimeMillis());
        }
        for(ConfiguredObjectAttribute<?,?> attr : _attributeTypes.values())
        {
            if(attr.isAutomated())
            {
                ConfiguredAutomatedAttribute<?,?> autoAttr = (ConfiguredAutomatedAttribute<?,?>)attr;
                if (autoAttr.isMandatory() && !(_attributes.containsKey(attr.getName())
                                            || !"".equals(autoAttr.defaultValue())))
                {
                    deleted();
                    throw new IllegalArgumentException("Mandatory attribute "
                                                       + attr.getName()
                                                       + " not supplied for instance of "
                                                       + getClass().getName());
                }
            }
        }
    }

    private Class<? extends ConfiguredObject> calculateBestFitInterface()
    {
        Set<Class<? extends ConfiguredObject>> candidates = new HashSet<Class<? extends ConfiguredObject>>();
        findBestFitInterface(getClass(), candidates);
        switch(candidates.size())
        {
            case 0:
                throw new ServerScopedRuntimeException("The configured object class " + getClass().getSimpleName() + " does not seem to implement an interface");
            case 1:
                return candidates.iterator().next();
            default:
                ArrayList<Class<? extends ConfiguredObject>> list = new ArrayList<>(candidates);

                throw new ServerScopedRuntimeException("The configured object class " + getClass().getSimpleName()
                        + " implements no single common interface which extends ConfiguredObject"
                        + " Identified candidates were : " + Arrays.toString(list.toArray()));
        }
    }

    private static final void findBestFitInterface(Class<? extends ConfiguredObject> clazz, Set<Class<? extends ConfiguredObject>> candidates)
    {
        for(Class<?> interfaceClass : clazz.getInterfaces())
        {
            if(ConfiguredObject.class.isAssignableFrom(interfaceClass))
            {
                checkCandidate((Class<? extends ConfiguredObject>) interfaceClass, candidates);
            }
        }
        if(clazz.getSuperclass() != null & ConfiguredObject.class.isAssignableFrom(clazz.getSuperclass()))
        {
            findBestFitInterface((Class<? extends ConfiguredObject>) clazz.getSuperclass(), candidates);
        }
    }

    private static void checkCandidate(final Class<? extends ConfiguredObject> interfaceClass,
                                       final Set<Class<? extends ConfiguredObject>> candidates)
    {
        if(!candidates.contains(interfaceClass))
        {
            Iterator<Class<? extends ConfiguredObject>> candidateIterator = candidates.iterator();

            while(candidateIterator.hasNext())
            {
                Class<? extends ConfiguredObject> existingCandidate = candidateIterator.next();
                if(existingCandidate.isAssignableFrom(interfaceClass))
                {
                    candidateIterator.remove();
                }
                else if(interfaceClass.isAssignableFrom(existingCandidate))
                {
                    return;
                }
            }

            candidates.add(interfaceClass);

        }
    }

    private void automatedSetValue(final String name, Object value)
    {
        try
        {
            final ConfiguredAutomatedAttribute attribute = (ConfiguredAutomatedAttribute) _attributeTypes.get(name);
            if(value == null && !"".equals(attribute.defaultValue()))
            {
                value = attribute.defaultValue();
            }
            ConfiguredObjectTypeRegistry.AutomatedField field = _automatedFields.get(name);

            if(field.getPreSettingAction() != null)
            {
                field.getPreSettingAction().invoke(this);
            }
            field.getField().set(this, attribute.convert(value, this));

            if(field.getPostSettingAction() != null)
            {
                field.getPostSettingAction().invoke(this);
            }
        }
        catch (IllegalAccessException e)
        {
            throw new ServerScopedRuntimeException("Unable to set the automated attribute " + name + " on the configure object type " + getClass().getName(),e);
        }
        catch (InvocationTargetException e)
        {
            if(e.getCause() instanceof RuntimeException)
            {
                throw (RuntimeException) e.getCause();
            }
            throw new ServerScopedRuntimeException("Unable to set the automated attribute " + name + " on the configure object type " + getClass().getName(),e);
        }
    }

    @Override
    public final void open()
    {
        if(_dynamicState.compareAndSet(DynamicState.UNINIT, DynamicState.OPENED))
        {
            doResolution(true);
            doValidation(true);
            doOpening(true);
            doAttainState();
        }
    }

    public void registerWithParents()
    {
        for(ConfiguredObject<?> parent : _parents.values())
        {
            if(parent instanceof AbstractConfiguredObject<?>)
            {
                ((AbstractConfiguredObject<?>)parent).registerChild(this);
            }
        }
    }

    protected void closeChildren()
    {
        applyToChildren(new Action<ConfiguredObject<?>>()
        {
            @Override
            public void performAction(final ConfiguredObject<?> child)
            {
                child.close();
            }
        });

        for(Collection<ConfiguredObject<?>> childList : _children.values())
        {
            childList.clear();
        }

        for(Map<UUID,ConfiguredObject<?>> childIdMap : _childrenById.values())
        {
            childIdMap.clear();
        }

        for(Map<String,ConfiguredObject<?>> childNameMap : _childrenByName.values())
        {
            childNameMap.clear();
        }

    }

    @Override
    public final void close()
    {
        if(_dynamicState.compareAndSet(DynamicState.OPENED, DynamicState.CLOSED))
        {
            closeChildren();
            onClose();
            unregister(false);

        }
    }

    protected void onClose()
    {
    }

    public final void create()
    {
        if(_dynamicState.compareAndSet(DynamicState.UNINIT, DynamicState.OPENED))
        {
            registerWithParents();
            final AuthenticatedPrincipal currentUser = SecurityManager.getCurrentUser();
            if(currentUser != null)
            {
                String currentUserName = currentUser.getName();
                _attributes.put(LAST_UPDATED_BY, currentUserName);
                _attributes.put(CREATED_BY, currentUserName);
                _lastUpdatedBy = currentUserName;
                _createdBy = currentUserName;
            }
            final long currentTime = System.currentTimeMillis();
            _attributes.put(LAST_UPDATED_TIME, currentTime);
            _attributes.put(CREATED_TIME, currentTime);
            _lastUpdatedTime = currentTime;
            _createdTime = currentTime;

            doResolution(true);
            doValidation(true);
            doCreation(true);
            doOpening(true);
            doAttainState();
        }
    }

    private void doAttainState()
    {
        applyToChildren(new Action<ConfiguredObject<?>>()
        {
            @Override
            public void performAction(final ConfiguredObject<?> child)
            {
                if (child instanceof AbstractConfiguredObject)
                {
                    ((AbstractConfiguredObject) child).doAttainState();
                }
            }
        });
        attainState();
    }

    protected void doOpening(final boolean skipCheck)
    {
        if(skipCheck || _dynamicState.compareAndSet(DynamicState.UNINIT,DynamicState.OPENED))
        {
            onOpen();
            notifyStateChanged(State.UNINITIALIZED, getState());
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doOpening(false);
                    }
                }
            });
            _openComplete = true;
        }
    }

    protected final void doValidation(final boolean skipCheck)
    {
        if(skipCheck || _dynamicState.get() != DynamicState.OPENED)
        {
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doValidation(false);
                    }
                }
            });
            onValidate();
        }
    }

    protected final void doResolution(final boolean skipCheck)
    {
        if(skipCheck || _dynamicState.get() != DynamicState.OPENED)
        {
            onResolve();
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doResolution(false);
                    }
                }
            });
        }
    }

    protected final void doCreation(final boolean skipCheck)
    {
        if(skipCheck || _dynamicState.get() != DynamicState.OPENED)
        {
            onCreate();
            applyToChildren(new Action<ConfiguredObject<?>>()
            {
                @Override
                public void performAction(final ConfiguredObject<?> child)
                {
                    if (child instanceof AbstractConfiguredObject)
                    {
                        ((AbstractConfiguredObject) child).doCreation(false);
                    }
                }
            });
        }
    }

    private void applyToChildren(Action<ConfiguredObject<?>> action)
    {
        for (Class<? extends ConfiguredObject> childClass : getModel().getChildTypes(getCategoryClass()))
        {
            Collection<? extends ConfiguredObject> children = getChildren(childClass);
            if (children != null)
            {
                for (ConfiguredObject<?> child : children)
                {
                    action.performAction(child);
                }
            }
        }
    }

    public void onValidate()
    {
    }

    protected void onResolve()
    {
        // If there is a context attribute, resolve it first, so that other attribute values
        // may support values containing references to context keys.
        ConfiguredObjectAttribute<?, ?> contextAttribute = _attributeTypes.get("context");
        if (contextAttribute != null && contextAttribute.isAutomated())
        {
            resolveAutomatedAttribute((ConfiguredAutomatedAttribute<?, ?>) contextAttribute);
        }

        for (ConfiguredObjectAttribute<?, ?> attr : _attributeTypes.values())
        {
            if (attr != contextAttribute && attr.isAutomated())
            {
                resolveAutomatedAttribute((ConfiguredAutomatedAttribute<?, ?>) attr);
            }
        }
    }

    private void resolveAutomatedAttribute(final ConfiguredAutomatedAttribute<?, ?> autoAttr)
    {
        String attrName = autoAttr.getName();
        if (_attributes.containsKey(attrName))
        {
            automatedSetValue(attrName, _attributes.get(attrName));
        }
        else if (!"".equals(autoAttr.defaultValue()))
        {
            automatedSetValue(attrName, autoAttr.defaultValue());
        }
    }

    private void attainStateIfResolved()
    {
        if(_openComplete)
        {
            attainState();
        }
    }

    protected void onOpen()
    {

    }

    protected void attainState()
    {
        State currentState = getState();
        State desiredState = getDesiredState();
        if(currentState != desiredState)
        {
            Method stateChangingMethod = getStateChangeMethod(currentState, desiredState);
            if(stateChangingMethod != null)
            {
                try
                {
                    stateChangingMethod.invoke(this);
                }
                catch (IllegalAccessException e)
                {
                    throw new ServerScopedRuntimeException("Unexpected access exception when calling state transition", e);
                }
                catch (InvocationTargetException e)
                {
                    Throwable underlying = e.getTargetException();
                    if(underlying instanceof RuntimeException)
                    {
                        throw (RuntimeException)underlying;
                    }
                    if(underlying instanceof Error)
                    {
                        throw (Error) underlying;
                    }
                    throw new ServerScopedRuntimeException("Unexpected checked exception when calling state transition", underlying);
                }
            }
        }
    }

    private Method getStateChangeMethod(final State currentState, final State desiredState)
    {
        Map<State, Method> stateChangeMethodMap = _stateChangeMethods.get(currentState);
        Method method = null;
        if(stateChangeMethodMap != null)
        {
            method = stateChangeMethodMap.get(desiredState);
        }
        return method;
    }


    protected void onCreate()
    {
    }

    public final UUID getId()
    {
        return _id;
    }

    public final String getName()
    {
        return _name;
    }

    public final boolean isDurable()
    {
        return _durable;
    }

    @Override
    public final ConfiguredObjectFactory getObjectFactory()
    {
        return _model.getObjectFactory();
    }

    @Override
    public final Model getModel()
    {
        return _model;
    }

    public Class<? extends ConfiguredObject> getCategoryClass()
    {
        return _category;
    }

    public Map<String,String> getContext()
    {
        return _context == null ? Collections.<String,String>emptyMap() : Collections.unmodifiableMap(_context);
    }

    public State getDesiredState()
    {
        return _desiredState;
    }


    private State setDesiredState(final State desiredState)
            throws IllegalStateTransitionException, AccessControlException
    {

        return runTask(new Task<State>()
                        {
                            @Override
                            public State execute()
                            {

                                State state = getState();
                                if(desiredState == getDesiredState() && desiredState != state)
                                {
                                    attainState();
                                    return getState();
                                }
                                else
                                {
                                    authoriseSetDesiredState(desiredState);

                                    setAttributes(Collections.<String, Object>singletonMap(DESIRED_STATE,
                                                                                           desiredState));

                                    if (setState(desiredState))
                                    {
                                        notifyStateChanged(state, desiredState);
                                        return desiredState;
                                    }
                                    else
                                    {
                                        return getState();
                                    }
                                }
                            }
                        });
    }

    /**
     * @return true when the state has been successfully updated to desiredState or false otherwise
     */
    protected boolean setState(State desiredState)
    {
        return getState() == desiredState;
    }


    protected void notifyStateChanged(final State currentState, final State desiredState)
    {
        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.stateChanged(this, currentState, desiredState);
            }
        }
    }

    public void addChangeListener(final ConfigurationChangeListener listener)
    {
        if(listener == null)
        {
            throw new NullPointerException("Cannot add a null listener");
        }
        synchronized (_changeListeners)
        {
            if(!_changeListeners.contains(listener))
            {
                _changeListeners.add(listener);
            }
        }
    }

    public boolean removeChangeListener(final ConfigurationChangeListener listener)
    {
        if(listener == null)
        {
            throw new NullPointerException("Cannot remove a null listener");
        }
        synchronized (_changeListeners)
        {
            return _changeListeners.remove(listener);
        }
    }

    protected void childAdded(ConfiguredObject child)
    {
        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.childAdded(this, child);
            }
        }
    }

    protected void childRemoved(ConfiguredObject child)
    {
        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.childRemoved(this, child);
            }
        }
    }

    protected void attributeSet(String attributeName, Object oldAttributeValue, Object newAttributeValue)
    {

        final AuthenticatedPrincipal currentUser = SecurityManager.getCurrentUser();
        if(currentUser != null)
        {
            _attributes.put(LAST_UPDATED_BY, currentUser.getName());
            _lastUpdatedBy = currentUser.getName();
        }
        final long currentTime = System.currentTimeMillis();
        _attributes.put(LAST_UPDATED_TIME, currentTime);
        _lastUpdatedTime = currentTime;

        synchronized (_changeListeners)
        {
            List<ConfigurationChangeListener> copy = new ArrayList<ConfigurationChangeListener>(_changeListeners);
            for(ConfigurationChangeListener listener : copy)
            {
                listener.attributeSet(this, attributeName, oldAttributeValue, newAttributeValue);
            }
        }
    }

    @Override
    public Object getAttribute(String name)
    {
        ConfiguredObjectAttribute<X,?> attr = (ConfiguredObjectAttribute<X, ?>) _attributeTypes.get(name);
        if(attr != null && (attr.isAutomated() || attr.isDerived()))
        {
            Object value = attr.getValue((X)this);
            if(value != null && attr.isSecure() &&
               !SecurityManager.isSystemProcess())
            {
                return SECURE_VALUES.get(value.getClass());
            }
            else
            {
                return value;
            }
        }
        else if(attr != null)
        {
            Object value = getActualAttribute(name);
            return value;
        }
        else
        {
            throw new IllegalArgumentException("Unknown attribute: '" + name + "'");
        }
    }

    @Override
    public String getDescription()
    {
        return _description;
    }

    @Override
    public LifetimePolicy getLifetimePolicy()
    {
        return _lifetimePolicy;
    }

    @Override
    public final Map<String, Object> getActualAttributes()
    {
        synchronized (_attributes)
        {
            return new HashMap<String, Object>(_attributes);
        }
    }

    private Object getActualAttribute(final String name)
    {
        synchronized (_attributes)
        {
            return _attributes.get(name);
        }
    }

    public Object setAttribute(final String name, final Object expected, final Object desired)
            throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        return _taskExecutor.run(new Task<Object>()
        {
            @Override
            public Object execute()
            {
                authoriseSetAttributes(createProxyForValidation(Collections.singletonMap(name, desired)),
                                       Collections.singleton(name));

                if (changeAttribute(name, expected, desired))
                {
                    attributeSet(name, expected, desired);
                    return desired;
                }
                else
                {
                    return getAttribute(name);
                }
            }
        });
    }

    protected boolean changeAttribute(final String name, final Object expected, final Object desired)
    {
        synchronized (_attributes)
        {
            Object currentValue = getAttribute(name);
            if((currentValue == null && expected == null)
               || (currentValue != null && currentValue.equals(expected)))
            {
                //TODO: don't put nulls
                _attributes.put(name, desired);
                ConfiguredObjectAttribute<?,?> attr = _attributeTypes.get(name);
                if(attr != null && attr.isAutomated())
                {
                    automatedSetValue(name, desired);
                }
                return true;
            }
            else
            {
                return false;
            }
        }
    }

    public <T extends ConfiguredObject> T getParent(final Class<T> clazz)
    {
        return (T) _parents.get(clazz);
    }

    private <T extends ConfiguredObject> void addParent(Class<T> clazz, T parent)
    {
        synchronized (_parents)
        {
            _parents.put(clazz, parent);
        }

    }

    public final Collection<String> getAttributeNames()
    {
        return ConfiguredObjectTypeRegistry.getAttributeNames(getClass());
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + " [id=" + _id + ", name=" + getName() + "]";
    }

    public final ConfiguredObjectRecord asObjectRecord()
    {
        return new ConfiguredObjectRecord()
        {
            @Override
            public UUID getId()
            {
                return AbstractConfiguredObject.this.getId();
            }

            @Override
            public String getType()
            {
                return getCategoryClass().getSimpleName();
            }

            @Override
            public Map<String, Object> getAttributes()
            {
                return Subject.doAs(SecurityManager.getSubjectWithAddedSystemRights(), new PrivilegedAction<Map<String, Object>>()
                {
                    @Override
                    public Map<String, Object> run()
                    {
                        Map<String,Object> attributes = new HashMap<String, Object>();
                        Map<String,Object> actualAttributes = getActualAttributes();
                        for(ConfiguredObjectAttribute<?,?> attr : _attributeTypes.values())
                        {
                            if(attr.isPersisted())
                            {
                                if(attr.isDerived())
                                {
                                    attributes.put(attr.getName(), getAttribute(attr.getName()));
                                }
                                else if(actualAttributes.containsKey(attr.getName()))
                                {
                                    Object value = actualAttributes.get(attr.getName());
                                    if(value instanceof ConfiguredObject)
                                    {
                                        value = ((ConfiguredObject)value).getId();
                                    }
                                    attributes.put(attr.getName(), value);
                                }
                            }
                        }
                        attributes.remove(ID);
                        return attributes;
                    }
                });
            }

            @Override
            public Map<String, ConfiguredObjectRecord> getParents()
            {
                Map<String, ConfiguredObjectRecord> parents = new LinkedHashMap<String, ConfiguredObjectRecord>();
                for(Class<? extends ConfiguredObject> parentClass : getModel().getParentTypes(getCategoryClass()))
                {
                    ConfiguredObject parent = getParent(parentClass);
                    if(parent != null)
                    {
                        parents.put(parentClass.getSimpleName(), parent.asObjectRecord());
                    }
                }
                return parents;
            }

            @Override
            public String toString()
            {
                return getClass().getSimpleName() + "[name=" + getName() + ", categoryClass=" + getCategoryClass() + ", type="
                        + getType() + ", id=" + getId() + "]";
            }
        };
    }

    @SuppressWarnings("unchecked")
    @Override
    public <C extends ConfiguredObject> C createChild(final Class<C> childClass, final Map<String, Object> attributes,
                                                      final ConfiguredObject... otherParents)
    {
        return _taskExecutor.run(new Task<C>() {

            @Override
            public C execute()
            {
                authoriseCreateChild(childClass, attributes, otherParents);
                C child = addChild(childClass, attributes, otherParents);
                if (child != null)
                {
                    childAdded(child);
                }
                return child;
            }
        });
    }

    protected <C extends ConfiguredObject> C addChild(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents)
    {
        throw new UnsupportedOperationException();
    }

    private <C extends ConfiguredObject> void registerChild(final C child)
    {

        Class categoryClass = child.getCategoryClass();
        UUID childId = child.getId();
        String name = child.getName();
        if(_childrenById.get(categoryClass).containsKey(childId))
        {
            throw new DuplicateIdException(child);
        }
        if(getModel().getParentTypes(categoryClass).size() == 1)
        {
            if (_childrenByName.get(categoryClass).containsKey(name))
            {
                throw new DuplicateNameException(child);
            }
            _childrenByName.get(categoryClass).put(name, child);
        }
        _children.get(categoryClass).add(child);
        _childrenById.get(categoryClass).put(childId,child);

    }

    public final void stop()
    {
        setDesiredState(State.STOPPED);
    }

    public final void delete()
    {
        setDesiredState(State.DELETED);
    }

    public final void start() { setDesiredState(State.ACTIVE); }

    protected void deleted()
    {
        unregister(true);
    }

    private void unregister(boolean removed)
    {
        for (ConfiguredObject<?> parent : _parents.values())
        {
            if (parent instanceof AbstractConfiguredObject<?>)
            {
                AbstractConfiguredObject<?> parentObj = (AbstractConfiguredObject<?>) parent;
                parentObj.unregisterChild(this);
                if(removed)
                {
                    parentObj.childRemoved(this);
                }
            }
        }
    }


    private <C extends ConfiguredObject> void unregisterChild(final C child)
    {
        Class categoryClass = child.getCategoryClass();
        _children.get(categoryClass).remove(child);
        _childrenById.get(categoryClass).remove(child.getId());
        _childrenByName.get(categoryClass).remove(child.getName());
    }

    @Override
    public final <C extends ConfiguredObject> C getChildById(final Class<C> clazz, final UUID id)
    {
        return (C) _childrenById.get(ConfiguredObjectTypeRegistry.getCategory(clazz)).get(id);
    }

    @Override
    public final <C extends ConfiguredObject> C getChildByName(final Class<C> clazz, final String name)
    {
        Class<? extends ConfiguredObject> categoryClass = ConfiguredObjectTypeRegistry.getCategory(clazz);
        if(getModel().getParentTypes(categoryClass).size() != 1)
        {
            throw new UnsupportedOperationException("Cannot use getChildByName for objects of category "
                                                    + categoryClass.getSimpleName() + " as it has more than one parent");
        }
        return (C) _childrenByName.get(categoryClass).get(name);
    }

    @Override
    public <C extends ConfiguredObject> Collection<C> getChildren(final Class<C> clazz)
    {
        return Collections.unmodifiableList((List<? extends C>) _children.get(clazz));
    }

    @Override
    public final TaskExecutor getTaskExecutor()
    {
        return _taskExecutor;
    }

    protected final <C> C runTask(Task<C> task)
    {
        return _taskExecutor.run(task);
    }

    protected void runTask(VoidTask task)
    {
        _taskExecutor.run(task);
    }

    protected final <T, E extends Exception> T runTask(TaskWithException<T,E> task) throws E
    {
        return _taskExecutor.run(task);
    }

    protected final <E extends Exception> void runTask(VoidTaskWithException<E> task) throws E
    {
        _taskExecutor.run(task);
    }


    @Override
    public void setAttributes(final Map<String, Object> attributes) throws IllegalStateException, AccessControlException, IllegalArgumentException
    {
        runTask(new VoidTask()
        {
            @Override
            public void execute()
            {
                authoriseSetAttributes(createProxyForValidation(attributes), attributes.keySet());
                changeAttributes(attributes);
            }
        });
    }

    protected void authoriseSetAttributes(final ConfiguredObject<?> proxyForValidation,
                                          final Set<String> modifiedAttributes)
    {

    }

    protected void changeAttributes(final Map<String, Object> attributes)
    {
        validateChange(createProxyForValidation(attributes), attributes.keySet());
        Collection<String> names = getAttributeNames();
        for (String name : names)
        {
            if (attributes.containsKey(name))
            {
                Object desired = attributes.get(name);
                Object expected = getAttribute(name);
                if(((_attributes.get(name) != null && !_attributes.get(name).equals(attributes.get(name)))
                     || attributes.get(name) != null)
                    && changeAttribute(name, expected, desired))
                {
                    attributeSet(name, expected, desired);
                }
            }
        }
    }

    protected void validateChange(final ConfiguredObject<?> proxyForValidation, final Set<String> changedAttributes)
    {
        if(!getId().equals(proxyForValidation.getId()))
        {
            throw new IllegalConfigurationException("Cannot change existing configured object id");
        }
    }

    private ConfiguredObject<?> createProxyForValidation(final Map<String, Object> attributes)
    {
        return (ConfiguredObject<?>) Proxy.newProxyInstance(getClass().getClassLoader(),
                                                            new Class<?>[]{_bestFitInterface},
                                                            new AttributeGettingHandler(attributes));
    }

    protected void authoriseSetDesiredState(State desiredState) throws AccessControlException
    {
        // allowed by default
    }

    protected <C extends ConfiguredObject> void authoriseCreateChild(Class<C> childClass, Map<String, Object> attributes, ConfiguredObject... otherParents) throws AccessControlException
    {
        // allowed by default
    }

    @Override
    public final String getLastUpdatedBy()
    {
        return _lastUpdatedBy;
    }

    @Override
    public final long getLastUpdatedTime()
    {
        return _lastUpdatedTime;
    }

    @Override
    public final String getCreatedBy()
    {
        return _createdBy;
    }

    @Override
    public final long getCreatedTime()
    {
        return _createdTime;
    }

    @Override
    public final String getType()
    {
        return _type;
    }


    @Override
    public Map<String,Number> getStatistics()
    {
        Collection<ConfiguredObjectStatistic> stats = ConfiguredObjectTypeRegistry.getStatistics(getClass());
        Map<String,Number> map = new HashMap<String,Number>();
        for(ConfiguredObjectStatistic stat : stats)
        {
            map.put(stat.getName(), (Number) stat.getValue(this));
        }
        return map;
    }


    public <Y extends ConfiguredObject<Y>> Y findConfiguredObject(Class<Y> clazz, String name)
    {
        Collection<Y> reachable = getModel().getReachableObjects(this, clazz);
        for(Y candidate : reachable)
        {
            if(candidate.getName().equals(name))
            {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public final <T> T getContextValue(Class<T> clazz, String propertyName)
    {
        AttributeValueConverter<T> converter = AttributeValueConverter.getConverter(clazz, clazz);
        return converter.convert("${" + propertyName + "}", this);
    }

    private OwnAttributeResolver getOwnAttributeResolver()
    {
        return _attributeResolver;
    }

    protected boolean isAttributePersisted(String name)
    {
        ConfiguredObjectAttribute<X,?> attr = (ConfiguredObjectAttribute<X, ?>) _attributeTypes.get(name);
        if(attr != null)
        {
            return attr.isPersisted();
        }
        return false;
    }

    //=========================================================================================

    static String interpolate(ConfiguredObject<?> object, String value)
    {
        Map<String,String> inheritedContext = new HashMap<String, String>();
        generateInheritedContext(object.getModel(), object, inheritedContext);
        return Strings.expand(value, false,
                              JSON_SUBSTITUTION_RESOLVER,
                              getOwnAttributeResolver(object),
                              new Strings.MapResolver(inheritedContext),
                              Strings.JAVA_SYS_PROPS_RESOLVER,
                              Strings.ENV_VARS_RESOLVER,
                              ConfiguredObjectTypeRegistry.getDefaultContextResolver());
    }

    private static OwnAttributeResolver getOwnAttributeResolver(final ConfiguredObject<?> object)
    {
        return object instanceof AbstractConfiguredObject
                ? ((AbstractConfiguredObject)object).getOwnAttributeResolver()
                : new OwnAttributeResolver(object);
    }

    static void generateInheritedContext(final Model model, final ConfiguredObject<?> object,
                                         final Map<String, String> inheritedContext)
    {
        Collection<Class<? extends ConfiguredObject>> parents =
                model.getParentTypes(object.getCategoryClass());
        if(parents != null && !parents.isEmpty())
        {
            ConfiguredObject parent = object.getParent(parents.iterator().next());
            if(parent != null)
            {
                generateInheritedContext(model, parent, inheritedContext);
            }
        }
        if(object.getContext() != null)
        {
            inheritedContext.putAll(object.getContext());
        }
    }


    private static final Strings.Resolver JSON_SUBSTITUTION_RESOLVER =
            Strings.createSubstitutionResolver("json:",
                                               new LinkedHashMap<String, String>()
                                               {
                                                   {
                                                       put("\\","\\\\");
                                                       put("\"","\\\"");
                                                   }
                                               });

    private static class OwnAttributeResolver implements Strings.Resolver
    {
        private static final Module _module;
        static
        {
            SimpleModule module= new SimpleModule("ConfiguredObjectSerializer", new Version(1,0,0,null));

            final JsonSerializer<ConfiguredObject> serializer = new JsonSerializer<ConfiguredObject>()
            {
                @Override
                public void serialize(final ConfiguredObject value,
                                      final JsonGenerator jgen,
                                      final SerializerProvider provider)
                        throws IOException, JsonProcessingException
                {
                    jgen.writeString(value.getId().toString());
                }
            };
            module.addSerializer(ConfiguredObject.class, serializer);

            _module = module;
        }


        public static final String PREFIX = "this:";
        private final ThreadLocal<Set<String>> _stack = new ThreadLocal<>();
        private final ConfiguredObject<?> _object;
        private final ObjectMapper _objectMapper;

        public OwnAttributeResolver(final ConfiguredObject<?> object)
        {
            _object = object;
            _objectMapper = new ObjectMapper();
            _objectMapper.registerModule(_module);
        }

        @Override
        public String resolve(final String variable, final Strings.Resolver resolver)
        {
            boolean clearStack = false;
            Set<String> currentStack = _stack.get();
            if(currentStack == null)
            {
                currentStack = new HashSet<>();
                _stack.set(currentStack);
                clearStack = true;
            }

            try
            {
                if(variable.startsWith(PREFIX))
                {
                    String attrName = variable.substring(PREFIX.length());
                    if(currentStack.contains(attrName))
                    {
                        throw new IllegalArgumentException("The value of attribute " + attrName + " is defined recursively");
                    }
                    else
                    {
                        currentStack.add(attrName);
                        Object returnVal = _object.getAttribute(attrName);
                        String returnString;
                        if(returnVal == null)
                        {
                            returnString =  null;
                        }
                        else if(returnVal instanceof Map || returnVal instanceof Collection)
                        {
                            try
                            {
                                StringWriter writer = new StringWriter();

                                _objectMapper.writeValue(writer, returnVal);

                                returnString = writer.toString();
                            }
                            catch (IOException e)
                            {
                                throw new IllegalArgumentException(e);
                            }
                        }
                        else if(returnVal instanceof ConfiguredObject)
                        {
                            returnString = ((ConfiguredObject)returnVal).getId().toString();
                        }
                        else
                        {
                            returnString = returnVal.toString();
                        }

                        return returnString;
                    }
                }
                else
                {
                    return null;
                }
            }
            finally
            {
                if(clearStack)
                {
                    _stack.remove();
                }

            }
        }
    }


    private class AttributeGettingHandler implements InvocationHandler
    {
        private Map<String,Object> _attributes;

        AttributeGettingHandler(final Map<String, Object> modifiedAttributes)
        {
            Map<String,Object> combinedAttributes = new HashMap<String, Object>(getActualAttributes());
            combinedAttributes.putAll(modifiedAttributes);
            _attributes = combinedAttributes;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable
        {

            if(method.isAnnotationPresent(ManagedAttribute.class))
            {
                ConfiguredObjectAttribute attribute = getAttributeFromMethod(method);
                return getValue(attribute);
            }
            else if(method.getName().equals("getAttribute") && args != null && args.length == 1 && args[0] instanceof String)
            {
                ConfiguredObjectAttribute attribute = _attributeTypes.get((String)args[0]);
                if(attribute != null)
                {
                    return getValue(attribute);
                }
                else
                {
                    return null;
                }
            }
            throw new UnsupportedOperationException("This class is only intended for value validation, and only getters on managed attributes are permitted.");
        }

        protected Object getValue(final ConfiguredObjectAttribute attribute)
        {
            if(attribute.isAutomated())
            {
                ConfiguredAutomatedAttribute autoAttr = (ConfiguredAutomatedAttribute)attribute;
                Object value = _attributes.get(attribute.getName());
                return attribute.convert(value == null && !"".equals(autoAttr.defaultValue()) ? autoAttr.defaultValue() : value , AbstractConfiguredObject.this);
            }
            else
            {
                return _attributes.get(attribute.getName());
            }
        }

        private ConfiguredObjectAttribute getAttributeFromMethod(final Method method)
        {
            for(ConfiguredObjectAttribute attribute : _attributeTypes.values())
            {
                if(attribute.getGetter().getName().equals(method.getName())
                   && !Modifier.isStatic(method.getModifiers()))
                {
                    return attribute;
                }
            }
            throw new ServerScopedRuntimeException("Unable to find attribute definition for method " + method.getName());
        }
    }

    protected final static class DuplicateIdException extends IllegalArgumentException
    {
        public DuplicateIdException(final ConfiguredObject<?> child)
        {
            super("Child of type " + child.getClass().getSimpleName() + " already exists with id of " + child.getId());
        }
    }

    protected final static class DuplicateNameException extends IllegalArgumentException
    {
        private final String _name;
        public DuplicateNameException(final ConfiguredObject<?> child)
        {
            super("Child of type " + child.getClass().getSimpleName() + " already exists with name of " + child.getName());
            _name = child.getName();
        }

        public String getName()
        {
            return _name;
        }
    }
}
