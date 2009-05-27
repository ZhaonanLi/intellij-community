package com.intellij.execution.impl;

import com.intellij.execution.*;
import com.intellij.execution.configurations.*;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.util.NullableFunction;
import com.intellij.util.containers.ContainerUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;


public class RunManagerImpl extends RunManagerEx implements JDOMExternalizable, ProjectComponent {
  private final Project myProject;

  private final Map<String, ConfigurationType> myTypesByName = new LinkedHashMap<String, ConfigurationType>();

  private Map<String, RunnerAndConfigurationSettingsImpl> myConfigurations =
      new LinkedHashMap<String, RunnerAndConfigurationSettingsImpl>(); // template configurations are not included here
  private final Map<Integer, Boolean> mySharedConfigurations = new TreeMap<Integer, Boolean>();
  /**
   * configurationID -> [BeforeTaskProviderName->BeforeRunTask]
   */
  private final Map<Integer, Map<Key<? extends BeforeRunTask>, BeforeRunTask>> myConfigurationToBeforeTasksMap = new TreeMap<Integer, Map<Key<? extends BeforeRunTask>, BeforeRunTask>>();

  private final Map<String, RunnerAndConfigurationSettingsImpl> myTemplateConfigurationsMap =
      new HashMap<String, RunnerAndConfigurationSettingsImpl>();
  private RunnerAndConfigurationSettingsImpl mySelectedConfiguration = null;
  private String mySelectedConfig = null;

  @NonNls
  protected static final String CONFIGURATION = "configuration";
  private ConfigurationType[] myTypes;
  private final RunManagerConfig myConfig;
  @NonNls
  protected static final String NAME_ATTR = "name";
  @NonNls
  protected static final String SELECTED_ATTR = "selected";
  @NonNls private static final String METHOD = "method";
  @NonNls private static final String OPTION = "option";
  @NonNls private static final String VALUE = "value";

  private List<Element> myUnloadedElements = null;
  private JDOMExternalizableStringList myOrder = new JDOMExternalizableStringList();

  public RunManagerImpl(final Project project,
                        PropertiesComponent propertiesComponent) {
    myConfig = new RunManagerConfig(propertiesComponent, this);
    myProject = project;

    initConfigurationTypes();
  }

  // separate method needed for tests
  public final void initializeConfigurationTypes(@NotNull final ConfigurationType[] factories) {
    Arrays.sort(factories, new Comparator<ConfigurationType>() {
      public int compare(final ConfigurationType o1, final ConfigurationType o2) {
        return o1.getDisplayName().compareTo(o2.getDisplayName());
      }
    });

    final ArrayList<ConfigurationType> types = new ArrayList<ConfigurationType>(Arrays.asList(factories));
    types.add(UnknownConfigurationType.INSTANCE);
    myTypes = types.toArray(new ConfigurationType[types.size()]);

    for (final ConfigurationType type : factories) {
      myTypesByName.put(type.getId(), type);
    }

    final UnknownConfigurationType broken = UnknownConfigurationType.INSTANCE;
    myTypesByName.put(broken.getId(), broken);
  }

  private void initConfigurationTypes() {
    final ConfigurationType[] configurationTypes = Extensions.getExtensions(ConfigurationType.CONFIGURATION_TYPE_EP);
    initializeConfigurationTypes(configurationTypes);
  }

  public void disposeComponent() {
  }

  public void initComponent() {
  }

  public void projectOpened() {
  }

  public RunnerAndConfigurationSettingsImpl createConfiguration(final String name, final ConfigurationFactory factory) {
    return createConfiguration(doCreateConfiguration(name, factory), factory);
  }

  protected RunConfiguration doCreateConfiguration(String name, ConfigurationFactory factory) {
    return factory.createConfiguration(name, getConfigurationTemplate(factory).getConfiguration());
  }

  public RunnerAndConfigurationSettingsImpl createConfiguration(final RunConfiguration runConfiguration,
                                                                final ConfigurationFactory factory) {
    RunnerAndConfigurationSettingsImpl template = getConfigurationTemplate(factory);
    myConfigurationToBeforeTasksMap.put(runConfiguration.getUniqueID(), getBeforeRunTasks(template.getConfiguration()));
    shareConfiguration(runConfiguration, isConfigurationShared(template));
    RunnerAndConfigurationSettingsImpl settings = new RunnerAndConfigurationSettingsImpl(this, runConfiguration, false);
    settings.importRunnerAndConfigurationSettings(template);
    return settings;
  }

  public void projectClosed() {
    myTemplateConfigurationsMap.clear();
  }

  public RunManagerConfig getConfig() {
    return myConfig;
  }

  public ConfigurationType[] getConfigurationFactories() {
    return myTypes.clone();
  }

  public ConfigurationType[] getConfigurationFactories(final boolean includeUnknown) {
    final ConfigurationType[] configurationTypes = myTypes.clone();
    if (!includeUnknown) {
      final List<ConfigurationType> types = new ArrayList<ConfigurationType>();
      for (ConfigurationType configurationType : configurationTypes) {
        if (!(configurationType instanceof UnknownConfigurationType)) {
          types.add(configurationType);
        }
      }

      return types.toArray(new ConfigurationType[types.size()]);
    }

    return configurationTypes;
  }

  /**
   * Template configuration is not included
   */
  public RunConfiguration[] getConfigurations(@NotNull final ConfigurationType type) {

    final List<RunConfiguration> array = new ArrayList<RunConfiguration>();
    for (RunnerAndConfigurationSettingsImpl myConfiguration : getSortedConfigurations()) {
      final RunConfiguration configuration = myConfiguration.getConfiguration();
      final ConfigurationType configurationType = configuration.getType();
      if (type.getId().equals(configurationType.getId())) {
        array.add(configuration);
      }
    }
    return array.toArray(new RunConfiguration[array.size()]);
  }

  public RunConfiguration[] getAllConfigurations() {
    RunConfiguration[] result = new RunConfiguration[myConfigurations.size()];
    int i = 0;
    for (Iterator<RunnerAndConfigurationSettingsImpl> iterator = getSortedConfigurations().iterator(); iterator.hasNext(); i++) {
      RunnerAndConfigurationSettings settings = iterator.next();
      result[i] = settings.getConfiguration();
    }

    return result;
  }

  @Nullable
  public RunnerAndConfigurationSettingsImpl getSettings(RunConfiguration configuration) {
    for (RunnerAndConfigurationSettingsImpl settings : getSortedConfigurations()) {
      if (settings.getConfiguration() == configuration) return settings;
    }
    return null;
  }

  /**
   * Template configuration is not included
   */
  public RunnerAndConfigurationSettingsImpl[] getConfigurationSettings(@NotNull final ConfigurationType type) {

    final LinkedHashSet<RunnerAndConfigurationSettingsImpl> array = new LinkedHashSet<RunnerAndConfigurationSettingsImpl>();
    for (RunnerAndConfigurationSettingsImpl configuration : getSortedConfigurations()) {
      final ConfigurationType configurationType = configuration.getType();
      if (configurationType != null && type.getId().equals(configurationType.getId())) {
        array.add(configuration);
      }
    }
    return array.toArray(new RunnerAndConfigurationSettingsImpl[array.size()]);
  }

  public RunnerAndConfigurationSettingsImpl getConfigurationTemplate(final ConfigurationFactory factory) {
    RunnerAndConfigurationSettingsImpl template = myTemplateConfigurationsMap.get(factory.getType().getId() + "." + factory.getName());
    if (template == null) {
      template = new RunnerAndConfigurationSettingsImpl(this, factory.createTemplateConfiguration(myProject, this), true);
      if (template.getConfiguration() instanceof UnknownRunConfiguration) {
        ((UnknownRunConfiguration) template.getConfiguration()).setDoNotStore(true);
      }
      myTemplateConfigurationsMap.put(factory.getType().getId() + "." + factory.getName(), template);
    }
    return template;
  }

  public void addConfiguration(RunnerAndConfigurationSettingsImpl settings, boolean shared, Map<Key<? extends BeforeRunTask>, BeforeRunTask> tasks) {
    final RunConfiguration configuration = settings.getConfiguration();
    final String configName = getUniqueName(configuration);

    myConfigurations.put(configName, settings);
    checkRecentsLimit();

    mySharedConfigurations.put(configuration.getUniqueID(), shared);
    setBeforeRunTasks(configuration, tasks);
  }

  void checkRecentsLimit() {    
    while (getTempConfigurations().length > getConfig().getRecentsLimit()) {
      for (Iterator<Map.Entry<String, RunnerAndConfigurationSettingsImpl>> it = myConfigurations.entrySet().iterator(); it.hasNext();) {
        Map.Entry<String, RunnerAndConfigurationSettingsImpl> entry = it.next();
        if (entry.getValue().isTemporary()) {
          it.remove();
          break;
        }
      }
    }
  }

  public static String getUniqueName(final RunConfiguration settings) {
    return settings.getType().getDisplayName() + "." + settings.getName();
  }

  public RunConfiguration getConfigurationByUniqueID(int id) {
    for (RunConfiguration each : getAllConfigurations()) {
      if (each.getUniqueID() == id) {
        return each;
      }
    }
    return null;
  }

  public RunConfiguration getConfigurationByUniqueName(String name) {
    for (RunConfiguration each : getAllConfigurations()) {
      if (getUniqueName(each).equals(name)) {
        return each;
      }
    }
    return null;
  }

  public void removeConfigurations(@NotNull final ConfigurationType type) {

    //for (Iterator<Pair<RunConfiguration, JavaProgramRunner>> it = myRunnerPerConfigurationSettings.keySet().iterator(); it.hasNext();) {
    //  final Pair<RunConfiguration, JavaProgramRunner> pair = it.next();
    //  if (type.equals(pair.getFirst().getType())) {
    //    it.remove();
    //  }
    //}
    for (Iterator<RunnerAndConfigurationSettingsImpl> it = getSortedConfigurations().iterator(); it.hasNext();) {
      final RunnerAndConfigurationSettings configuration = it.next();
      final ConfigurationType configurationType = configuration.getType();
      if (configurationType != null && type.getId().equals(configurationType.getId())) {
        it.remove();
      }
    }
  }

  private Collection<RunnerAndConfigurationSettingsImpl> getSortedConfigurations() {
    if (myOrder != null && !myOrder.isEmpty()) { //compatibility
      final HashMap<String, RunnerAndConfigurationSettingsImpl> settings =
          new HashMap<String, RunnerAndConfigurationSettingsImpl>(myConfigurations); //sort shared and local configurations
      myConfigurations.clear();
      final List<String> order = new ArrayList<String>(settings.keySet());
      Collections.sort(order, new Comparator<String>() {
        public int compare(final String o1, final String o2) {
          return myOrder.indexOf(o1) - myOrder.indexOf(o2);
        }
      });
      for (String configName : order) {
        myConfigurations.put(configName, settings.get(configName));
      }
      myOrder = null;
    }
    return myConfigurations.values();
  }


  public RunnerAndConfigurationSettingsImpl getSelectedConfiguration() {
    if (mySelectedConfiguration == null && mySelectedConfig != null) {
      mySelectedConfiguration = myConfigurations.get(mySelectedConfig);
      mySelectedConfig = null;
    }
    return mySelectedConfiguration;
  }

  public void setSelectedConfiguration(final RunnerAndConfigurationSettingsImpl configuration) {
    mySelectedConfiguration = configuration;
  }

  public static boolean canRunConfiguration(@NotNull final RunnerAndConfigurationSettingsImpl configuration, final @NotNull Executor executor) {
    try {
      configuration.checkSettings(executor);
    }
    catch (RuntimeConfigurationError er) {
      return false;
    }
    catch (RuntimeConfigurationException e) {
      return true;
    }
    return true;
  }

  public void writeExternal(@NotNull final Element parentNode) throws WriteExternalException {

    writeContext(parentNode);
    for (final RunnerAndConfigurationSettingsImpl runnerAndConfigurationSettings : myTemplateConfigurationsMap.values()) {
      if (runnerAndConfigurationSettings.getConfiguration() instanceof UnknownRunConfiguration) {
        if (((UnknownRunConfiguration) runnerAndConfigurationSettings.getConfiguration()).isDoNotStore()) {
          continue;
        }
      }
      
      addConfigurationElement(parentNode, runnerAndConfigurationSettings);
    }

    final Collection<RunnerAndConfigurationSettingsImpl> configurations = getStableConfigurations().values();
    for (RunnerAndConfigurationSettingsImpl configuration : configurations) {
      if (!isConfigurationShared(configuration)) {
        addConfigurationElement(parentNode, configuration);
      }
    }

    final JDOMExternalizableStringList order = new JDOMExternalizableStringList();

    //temp && stable configurations, !unknown
    order.addAll(ContainerUtil.findAll(myConfigurations.keySet(), new Condition<String>() {
      public boolean value(final String s) {
        return !s.startsWith(UnknownConfigurationType.NAME);
      }
    }));

    order.writeExternal(parentNode);

    if (myUnloadedElements != null) {
      for (Element unloadedElement : myUnloadedElements) {
        parentNode.addContent((Element)unloadedElement.clone());
      }
    }
  }

  public void writeContext(Element parentNode) throws WriteExternalException {
    for (RunnerAndConfigurationSettingsImpl configurationSettings : myConfigurations.values()) {
      if (configurationSettings.isTemporary()) {
        addConfigurationElement(parentNode, configurationSettings, CONFIGURATION);
      }
    }
    if (mySelectedConfiguration != null) {
      parentNode.setAttribute(SELECTED_ATTR, getUniqueName(mySelectedConfiguration.getConfiguration()));
    }
  }

  public void addConfigurationElement(final Element parentNode, RunnerAndConfigurationSettingsImpl template) throws WriteExternalException {
    addConfigurationElement(parentNode, template, CONFIGURATION);
  }

  private void addConfigurationElement(final Element parentNode, RunnerAndConfigurationSettingsImpl template, String elementType)
      throws WriteExternalException {
    final Element configurationElement = new Element(elementType);
    parentNode.addContent(configurationElement);
    template.writeExternal(configurationElement);
    if (!(template.getConfiguration() instanceof UnknownRunConfiguration)) {
      final Map<Key<? extends BeforeRunTask>, BeforeRunTask> tasks = getBeforeRunTasks(template.getConfiguration());
      final List<Key<? extends BeforeRunTask>> order = new ArrayList<Key<? extends BeforeRunTask>>(tasks.keySet());
      Collections.sort(order, new Comparator<Key<? extends BeforeRunTask>>() {
        public int compare(Key<? extends BeforeRunTask> o1, Key<? extends BeforeRunTask> o2) {
          return o1.toString().compareToIgnoreCase(o2.toString());
        }
      });
      final Element methodsElement = new Element(METHOD);
      for (Key<? extends BeforeRunTask> providerID : order) {
        final Element child = new Element(OPTION);
        child.setAttribute(NAME_ATTR, providerID.toString());
        final BeforeRunTask beforeRunTask = tasks.get(providerID);
        beforeRunTask.writeExternal(child);
        methodsElement.addContent(child);
      }
      configurationElement.addContent(methodsElement);
    }
  }

  public void readExternal(final Element parentNode) throws InvalidDataException {
    clear();

    final List children = parentNode.getChildren();
    for (final Object aChildren : children) {
      final Element element = (Element)aChildren;
      if (loadConfiguration(element, false) == null && Comparing.strEqual(element.getName(), CONFIGURATION)) {
        if (myUnloadedElements == null) myUnloadedElements = new ArrayList<Element>(2);
        myUnloadedElements.add(element);
      }
    }

    myOrder.readExternal(parentNode);

    mySelectedConfig = parentNode.getAttributeValue(SELECTED_ATTR);
  }

  public void readContext(Element parentNode) throws InvalidDataException {
    final List children = parentNode.getChildren();
    mySelectedConfig = parentNode.getAttributeValue(SELECTED_ATTR);
    for (final Object aChildren : children) {
      final Element element = (Element)aChildren;
      if (mySelectedConfig == null && Boolean.valueOf(element.getAttributeValue(SELECTED_ATTR)).booleanValue()) {
        mySelectedConfig = element.getAttributeValue(RunnerAndConfigurationSettingsImpl.NAME_ATTR);
      }
      loadConfiguration(element, false);
    }
    if (mySelectedConfig != null) {
      RunnerAndConfigurationSettingsImpl configurationSettings = myConfigurations.get(mySelectedConfig);
      if (configurationSettings != null) {
        mySelectedConfiguration = null;
      }
    }
  }

  private void clear() {
    myConfigurations.clear();
    myUnloadedElements = null;
    myConfigurationToBeforeTasksMap.clear();
    mySharedConfigurations.clear();
  }

  @Nullable
  public RunnerAndConfigurationSettingsImpl loadConfiguration(final Element element, boolean isShared) throws InvalidDataException {
    RunnerAndConfigurationSettingsImpl settings = new RunnerAndConfigurationSettingsImpl(this);
    settings.readExternal(element);
    ConfigurationFactory factory = settings.getFactory();
    if (factory == null) {
      return null;
    }

    final Element methodsElement = element.getChild(METHOD);
    final Map<Key<? extends BeforeRunTask>, BeforeRunTask> map = readStepsBeforeRun(methodsElement, settings);
    if (settings.isTemplate()) {
      myTemplateConfigurationsMap.put(factory.getType().getId() + "." + factory.getName(), settings);
      setBeforeRunTasks(settings.getConfiguration(), map);
    }
    else {
      if (Boolean.valueOf(element.getAttributeValue(SELECTED_ATTR)).booleanValue()) { //to support old style
        mySelectedConfiguration = settings;
      }
      addConfiguration(settings, isShared, map);
    }
    return settings;
  }

  @NotNull
  private Map<Key<? extends BeforeRunTask>, BeforeRunTask> readStepsBeforeRun(final Element child, RunnerAndConfigurationSettingsImpl settings) {
    final Map<Key<? extends BeforeRunTask>, BeforeRunTask> map = new HashMap<Key<? extends BeforeRunTask>, BeforeRunTask>();
    if (child != null) {
      for (Object o : child.getChildren(OPTION)) {
        final Element methodElement = (Element)o;
        final String providerName = methodElement.getAttributeValue(NAME_ATTR);
        Key<? extends BeforeRunTask> id = getProviderKey(providerName);
        if (id != null) {
          final BeforeRunTask beforeRunTask = getProvider(id).createTask(settings.getConfiguration());
          beforeRunTask.readExternal(methodElement);
          map.put(id, beforeRunTask);
        }
      }
    }
    return map;
  }


  @Nullable
  public ConfigurationType getConfigurationType(final String typeName) {
    return myTypesByName.get(typeName);
  }

  @Nullable
  public ConfigurationFactory getFactory(final String typeName, String factoryName) {
    final ConfigurationType type = myTypesByName.get(typeName);
    if (factoryName == null) {
      factoryName = type != null ? type.getConfigurationFactories()[0].getName() : null;
    }
    return findFactoryOfTypeNameByName(typeName, factoryName);
  }


  @Nullable
  private ConfigurationFactory findFactoryOfTypeNameByName(final String typeName, final String factoryName) {
    ConfigurationType type = myTypesByName.get(typeName);
    if (type == null) {
      type = myTypesByName.get(UnknownConfigurationType.NAME);
    }

    return findFactoryOfTypeByName(type, factoryName);
  }

  @Nullable
  private static ConfigurationFactory findFactoryOfTypeByName(final ConfigurationType type, final String factoryName) {
    if (factoryName == null) return null;
    
    if (type instanceof UnknownConfigurationType) {
      return type.getConfigurationFactories()[0];
    }

    final ConfigurationFactory[] factories = type.getConfigurationFactories();
    for (final ConfigurationFactory factory : factories) {
      if (factoryName.equals(factory.getName())) return factory;
    }

    return null;
  }

  @NotNull
  public String getComponentName() {
    return "RunManager";
  }

  public void setTemporaryConfiguration(@NotNull final RunnerAndConfigurationSettingsImpl tempConfiguration) {
    tempConfiguration.setTemporary(true);

    addConfiguration(tempConfiguration, isConfigurationShared(tempConfiguration),
                     getBeforeRunTasks(tempConfiguration.getConfiguration()));
    setActiveConfiguration(tempConfiguration);
  }

  public void setActiveConfiguration(final RunnerAndConfigurationSettingsImpl configuration) {
    setSelectedConfiguration(configuration);
  }

  public Map<String, RunnerAndConfigurationSettingsImpl> getStableConfigurations() {
    final Map<String, RunnerAndConfigurationSettingsImpl> result =
        new LinkedHashMap<String, RunnerAndConfigurationSettingsImpl>(myConfigurations);
    for (Iterator<Map.Entry<String, RunnerAndConfigurationSettingsImpl>> it = result.entrySet().iterator(); it.hasNext();) {
      Map.Entry<String, RunnerAndConfigurationSettingsImpl> entry = it.next();
      if (entry.getValue().isTemporary()) {
        it.remove();
      }
    }
    return result;
  }

  public boolean isTemporary(final RunConfiguration configuration) {
    return Arrays.asList(getTempConfigurations()).contains(configuration);
  }

  public boolean isTemporary(RunnerAndConfigurationSettingsImpl settings) {
    return settings.isTemporary();
  }

  public RunConfiguration[] getTempConfigurations() {
    List<RunConfiguration> configurations = ContainerUtil.mapNotNull(myConfigurations.values(), new NullableFunction<RunnerAndConfigurationSettingsImpl, RunConfiguration>() {
      public RunConfiguration fun(RunnerAndConfigurationSettingsImpl settings) {
        return settings.isTemporary() ? settings.getConfiguration() : null;
      }
    });
    return configurations.toArray(new RunConfiguration[configurations.size()]);
  }

  public void makeStable(final RunConfiguration configuration) {
    RunnerAndConfigurationSettingsImpl settings = getSettings(configuration);
    if (settings != null) {
      settings.setTemporary(false);
    }
  }

  public RunnerAndConfigurationSettings createRunConfiguration(String name, ConfigurationFactory type) {
    return createConfiguration(name, type);
  }

  public boolean isConfigurationShared(final RunnerAndConfigurationSettingsImpl settings) {
    Boolean shared = mySharedConfigurations.get(settings.getConfiguration().getUniqueID());
    if (shared == null) {
      final RunnerAndConfigurationSettingsImpl template = getConfigurationTemplate(settings.getFactory());
      shared = mySharedConfigurations.get(template.getConfiguration().getUniqueID());
    }
    return shared != null && shared.booleanValue();
  }

  public <T extends BeforeRunTask> Collection<T> getBeforeRunTasks(Key<T> taskProviderID, boolean includeOnlyActiveTasks) {
    final Collection<T> tasks = new ArrayList<T>();
    if (includeOnlyActiveTasks) {
      final Set<RunnerAndConfigurationSettingsImpl> checkedTemplates = new HashSet<RunnerAndConfigurationSettingsImpl>();
      for (RunnerAndConfigurationSettingsImpl settings : myConfigurations.values()) {
        final BeforeRunTask runTask = getBeforeRunTask(settings.getConfiguration(), taskProviderID);
        if (runTask.isEnabled()) {
          tasks.add((T)runTask);
        }
        else {
          final RunnerAndConfigurationSettingsImpl template = getConfigurationTemplate(settings.getFactory());
          if (!checkedTemplates.contains(template)) {
            checkedTemplates.add(template);
            final BeforeRunTask templateTask = getBeforeRunTask(template.getConfiguration(), taskProviderID);
            if (templateTask.isEnabled()) {
              tasks.add((T)templateTask);
            }
          }
        }
      }
    }
    else {
      for (RunnerAndConfigurationSettingsImpl settings : myTemplateConfigurationsMap.values()) {
        tasks.add((T)getBeforeRunTask(settings.getConfiguration(), taskProviderID));
      }
      for (RunnerAndConfigurationSettingsImpl settings : myConfigurations.values()) {
        tasks.add((T)getBeforeRunTask(settings.getConfiguration(), taskProviderID));
      }
    }
    return tasks;
  }

  public <T extends BeforeRunTask> T getBeforeRunTask(RunConfiguration settings, Key<T> taskProviderID) {
    Map<Key<? extends BeforeRunTask>, BeforeRunTask> tasks = myConfigurationToBeforeTasksMap.get(settings.getUniqueID());
    if (tasks == null) {
      tasks = getBeforeRunTasks(settings);
    }
    return (T)tasks.get(taskProviderID);
  }

  public Map<Key<? extends BeforeRunTask>, BeforeRunTask> getBeforeRunTasks(final RunConfiguration settings) {
    final Map<Key<? extends BeforeRunTask>, BeforeRunTask> tasks = myConfigurationToBeforeTasksMap.get(settings.getUniqueID());
    if (tasks != null) {
      final Map<Key<? extends BeforeRunTask>, BeforeRunTask> _tasks = new HashMap<Key<? extends BeforeRunTask>, BeforeRunTask>();
      for (Map.Entry<Key<? extends BeforeRunTask>, BeforeRunTask> entry : tasks.entrySet()) {
        _tasks.put(entry.getKey(), entry.getValue().clone());
      }
      return _tasks;
    }

    final RunnerAndConfigurationSettingsImpl template = getConfigurationTemplate(settings.getFactory());
    final Map<Key<? extends BeforeRunTask>, BeforeRunTask> templateTasks = myConfigurationToBeforeTasksMap.get(template.getConfiguration().getUniqueID());
    if (templateTasks != null) {
      final Map<Key<? extends BeforeRunTask>, BeforeRunTask> _tasks = new HashMap<Key<? extends BeforeRunTask>, BeforeRunTask>();
      for (Map.Entry<Key<? extends BeforeRunTask>, BeforeRunTask> entry : templateTasks.entrySet()) {
        _tasks.put(entry.getKey(), entry.getValue().clone());
      }
      return _tasks;
    }

    final Map<Key<? extends BeforeRunTask>, BeforeRunTask> _tasks = new HashMap<Key<? extends BeforeRunTask>, BeforeRunTask>();
    for (BeforeRunTaskProvider<? extends BeforeRunTask> provider : Extensions.getExtensions(BeforeRunTaskProvider.EXTENSION_POINT_NAME, myProject)) {
      BeforeRunTask task = provider.createTask(settings);
      Key<? extends BeforeRunTask> providerID = provider.getId();
      _tasks.put(providerID, task);
      settings.getFactory().configureBeforeRunTaskDefaults(providerID, task);
    }
    return _tasks;
  }

  public void shareConfiguration(final RunConfiguration runConfiguration, final boolean shareConfiguration) {
    mySharedConfigurations.put(runConfiguration.getUniqueID(), shareConfiguration);
  }

  public final void setBeforeRunTasks(final RunConfiguration runConfiguration, Map<Key<? extends BeforeRunTask>, BeforeRunTask> tasks) {
    final Map<Key<? extends BeforeRunTask>, BeforeRunTask> taskMap = getBeforeRunTasks(runConfiguration);
    for (Map.Entry<Key<? extends BeforeRunTask>, BeforeRunTask> entry : tasks.entrySet()) {
      if (taskMap.containsKey(entry.getKey())) {
        taskMap.put(entry.getKey(), entry.getValue());
      }
    }
    myConfigurationToBeforeTasksMap.put(runConfiguration.getUniqueID(), taskMap);
  }

  public final void resetBeforeRunTasks(final RunConfiguration runConfiguration) {
    myConfigurationToBeforeTasksMap.remove(runConfiguration.getUniqueID());
  }

  public void addConfiguration(final RunnerAndConfigurationSettingsImpl settings, final boolean isShared) {
    addConfiguration(settings, isShared, Collections.<Key<? extends BeforeRunTask>, BeforeRunTask>emptyMap());
  }

  public static RunManagerImpl getInstanceImpl(final Project project) {
    return (RunManagerImpl)RunManager.getInstance(project);
  }

  public void removeNotExistingSharedConfigurations(final Set<String> existing) {
    for (Iterator<Map.Entry<String, RunnerAndConfigurationSettingsImpl>> it = myConfigurations.entrySet().iterator(); it.hasNext();) {
      Map.Entry<String, RunnerAndConfigurationSettingsImpl> c = it.next();
      final RunnerAndConfigurationSettingsImpl o = c.getValue();
      if (!o.isTemplate() && isConfigurationShared(o) && !existing.contains(getUniqueName(o.getConfiguration()))) {
        it.remove();
      }
    }
  }

  private Map<Key<? extends BeforeRunTask>, BeforeRunTaskProvider> myBeforeStepsMap;
  private Map<String, Key<? extends BeforeRunTask>> myProviderKeysMap;

  private synchronized BeforeRunTaskProvider getProvider(Key<? extends BeforeRunTask> providerId) {
    if (myBeforeStepsMap == null) {
      initProviderMaps();
    }
    return myBeforeStepsMap.get(providerId);
  }

  private synchronized Key<? extends BeforeRunTask> getProviderKey(String keyString) {
    if (myProviderKeysMap == null) {
      initProviderMaps();
    }
    return myProviderKeysMap.get(keyString);
  }

  private void initProviderMaps() {
    myBeforeStepsMap = new HashMap<Key<? extends BeforeRunTask>, BeforeRunTaskProvider>();
    myProviderKeysMap = new HashMap<String, Key<? extends BeforeRunTask>>();
    for (BeforeRunTaskProvider<? extends BeforeRunTask> provider : Extensions.getExtensions(BeforeRunTaskProvider.EXTENSION_POINT_NAME, myProject)) {
      final Key<? extends BeforeRunTask> id = provider.getId();
      myBeforeStepsMap.put(id, provider);
      myProviderKeysMap.put(id.toString(), id);
    }
  }


}
