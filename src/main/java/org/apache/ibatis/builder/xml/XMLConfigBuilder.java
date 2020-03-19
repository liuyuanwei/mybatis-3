/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 * 继承 BaseBuilder 抽象类，XML 配置构建器，主要负责解析 mybatis-config.xml 配置文件
 * 即对应 https://mybatis.org/mybatis-3/zh/configuration.html
 */
public class XMLConfigBuilder extends BaseBuilder {

    // 是否已解析
    private boolean parsed; // 判断全局配置文件是否被解析过，初次被构造函数初始化时是 false
    // 基于 Java XPath 解析器
    private final XPathParser parser; // 真正的对xml全局配置文件进行解析的对象
    private String environment; // 指定数据库环境，如果没有指定默认是default
    // ReflectorFactory 对象
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        // <1> 创建 Configuration 对象
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        // <2> 设置 Configuration 的 variables 属性
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    public Configuration parse() {
        // <1.1> 如果为true，说明被解析过，抛出 BuilderException 异常
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        // <1.2> 标记已解析
        parsed = true;
        // 解析全局配置文件，从全局配置文件的configuration根标签开始解析
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    private void parseConfiguration(XNode root) {
        try {
            //issue #117 read properties first
            // 解析properties标签，解析成properties对象，并保存到XPathParser 和 Configuration 的 variables变量中
            propertiesElement(root.evalNode("properties"));
            // 解析settings标签，把<settings>标签也解析成了一个 Properties 对象，对于<settings>标签的子标签的处理在后面
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            // 加载自定义 VFS 实现类
            loadCustomVfs(settings);// loadCustomVfs 是获取 Vitual File System 的自定义实现类
            loadCustomLogImpl(settings); // loadCustomLogImpl 是根据<logImpl>标签获取日志的实现类，我们可以用到很多的日志的方案，包括 LOG4J，LOG4J2，SLF4J 等等。这里生成了一个 Log 接口的实现类，并且赋值到 Configuration 中。
            // 解析typeAliases标签，把<类的别名和类的关系，我们保存到Configuration 的 TypeAliasRegistry 对象里面。
            typeAliasesElement(root.evalNode("typeAliases"));
            // 接下来就是解析<plugins>标签，比如 Pagehelper 的翻页插件，或者我们自定义的插件。
            // 【<plugins>标签里面只有<plugin>标签，<plugin>标签里面只有<property>标签】。
            //标签解析完以后，会生成一个 Interceptor 对象，并且添加到 Configuration 的InterceptorChain 属性里面，InterceptorChain只有一个属性——List<Interceptor>。
            pluginElement(root.evalNode("plugins"));
            // 接 下 来 的 两 个 标 签 是 用 来 实 例 化 对 象 用 的 ， <objectFactory> 和
            //<objectWrapperFactory> 这 两 个 标 签 ， 分 别 生 成 ObjectFactory 、
            //ObjectWrapperFactory 对象，同样设置到 Configuration 的属性里面。
            objectFactoryElement(root.evalNode("objectFactory"));
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
            // 解析 reflectorFactory 标签，生成 ReflectorFactory 对象（在官方 3.5.1 的 pdf 文档里面没有找到这个配置）。
            reflectorFactoryElement(root.evalNode("reflectorFactory"));
            // 二级标签里面有很多的配置，比如二级缓存，延迟加载，自动生成主键这些。需要注意的是，我们之前提到的所有的默认值，都是在这里赋值的。
            //所有的值，都会赋值到 Configuration 的属性里面去。
            settingsElement(settings);
            // 解析<environments>标签
            // 一个 environment 就是对应一个数据源，所以在这里我们会根据配置的<transactionManager>创建一个事务工厂，
            // 根据<dataSource>标签创建一个数据源，最后把这两个对象设置成 Environment 对象的属性，放到 Configuration 里面。
            environmentsElement(root.evalNode("environments"));
            // 解析 databaseIdProvider 标签，生成 DatabaseIdProvider 对象（用来支持不同厂商的数据库）。
            // 参考https://mybatis.org/mybatis-3/zh/configuration.html#databaseIdProvider
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));
            // 解析 typeHandlers 标签，
            // 最后我们得到的是 JavaType 和 JdbcType，以及用来做相互映射的 TypeHandler 之间的映射关系。
            // 最后存放在 configuraion的TypeHandlerRegistry 对象里面。
            typeHandlerElement(root.evalNode("typeHandlers"));
            // <mappers>标签的解析。
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    /**
     * 将 <setting /> 标签解析为 Properties 对象。
     * @param context
     * @return
     */
    private Properties settingsAsProperties(XNode context) {
        // 如果setting为空（没有设置）返回一个空的 Properties 对象
        if (context == null) {
            return new Properties();
        }
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        // 校验每个属性，在 Configuration 中，有相应的 setting 方法，否则抛出 BuilderException 异常
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    /**
     * 加载自定义 VFS 实现类
     * @param props
     * @throws ClassNotFoundException
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        // 获得 vfsImpl 属性
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            // 使用 , 作为分隔符，拆成 VFS 类名的数组
            String[] clazzes = value.split(",");
            // 遍历 VFS 类名的数组
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    // 获得 VFS 类
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
                    // 设置到 Configuration 中
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    private void loadCustomLogImpl(Properties props) {
        Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
    }

    /**
     * 解析typeAliases标签，把<类的别名和类的关系，我们保存到Configuration 的 TypeAliasRegistry 对象里面。
     * @param parent
     */
    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                // 指定为包的情况下，注册包下的每个类
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                } else { // 指定为类的情况下，直接注册类和别名
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type); // 获得类是否存在
                        // 注册到 typeAliasRegistry 中
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) { // 若类不存在，则抛出 BuilderException 异常
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 接下来就是解析<plugins>标签，比如 Pagehelper 的翻页插件，或者我们自定义的插件。
     * 【<plugins>标签里面只有<plugin>标签，<plugin>标签里面只有<property>标签】。
     *标签解析完以后，会生成一个 Interceptor 对象，并且添加到 Configuration 的InterceptorChain 属性里面，InterceptorChain只有一个属性——List<Interceptor>。
     * @param parent
     * @throws Exception
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            // 遍历 <plugins /> 标签
            for (XNode child : parent.getChildren()) {
                String interceptor = child.getStringAttribute("interceptor");
                Properties properties = child.getChildrenAsProperties();
                // <1> 创建 Interceptor 对象，并设置属性
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);
                // <2> 添加到 configuration 中
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ObjectFactory 的实现类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties properties = context.getChildrenAsProperties();
            // <1> 创建 ObjectFactory 对象，并设置 Properties 属性
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            factory.setProperties(properties);
            // <2> 设置 Configuration 的 objectFactory 属性
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // // 获得 ObjectWrapperFactory 的实现类
            String type = context.getStringAttribute("type");
            // <1> 创建 ObjectWrapperFactory 对象
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 objectWrapperFactory 属性
            configuration.setObjectWrapperFactory(factory);
        }
    }

    /**
     * 解析 reflectorFactory 标签，生成 ReflectorFactory 对象
     * @param context
     * @throws Exception
     */
    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ReflectorFactory 的实现类
            String type = context.getStringAttribute("type");
            // 创建 ReflectorFactory 对象
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 reflectorFactory 属性
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     *
     * @param context
     * @throws Exception
     * 解析 <properties /> 节点。大体逻辑如下：
     * 1 解析 <properties /> 标签，成 Properties 对象。
     * 2 覆盖 configuration 中的 Properties 对象到上面的结果。
     * 3 设置结果到 parser 和 configuration 中。
     */
    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 读取properties子标签们，为 Properties 对象
            Properties defaults = context.getChildrenAsProperties();
            // 读取 resource 和 url 属性
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");
            if (resource != null && url != null) { // resource 和 url 都存在的情况下，抛出 BuilderException 异常
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            // 读取本地 Properties 配置文件到 defaults 中。
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) { // 读取远程 Properties 配置文件到 defaults 中
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            // 覆盖 configuration 中的 Properties 对象到 defaults 中。
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            // 设置 defaults 到 parser 和 configuration 中。
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    private void settingsElement(Properties props) {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * 解析<environments>标签
     * 一个 environment 就是对应一个数据源，所以在这里我们会根据配置的<transactionManager>创建一个事务工厂，
     * 根据<dataSource>标签创建一个数据源，最后把这两个对象设置成 Environment 对象的属性，放到 Configuration 里面。
     * @param context
     * @throws Exception
     */
    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            // <1> environment 属性非空，从 default 属性获得
            if (environment == null) { // 如果environment，则使用默认"default"数据源的environment标签的id
                environment = context.getStringAttribute("default");
            }
            // 遍历 XNode 节点
            for (XNode child : context.getChildren()) {
                String id = child.getStringAttribute("id");
                // <2> 判断 environment 是否匹配
                if (isSpecifiedEnvironment(id)) { // 如果匹配到environment的id
                    // 创建事务工厂 <3> 解析 `<transactionManager />` 标签，返回 TransactionFactory 对象
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    // 创建数据源工厂 解析 `<dataSource />` 标签，返回 DataSourceFactory 对象
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    // 获取数据源
                    DataSource dataSource = dsFactory.getDataSource();
                    // 构建Environment对象，并且保存到configuration的Environment对象中
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    // <6> 构造 Environment 对象，并设置到 configuration 中
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     *解析 databaseIdProvider 标签，生成 DatabaseIdProvider 对象（用来支持不同厂商的数据库）。
     * 参考https://mybatis.org/mybatis-3/zh/configuration.html#databaseIdProvider
     * @param context
     * @throws Exception
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            // <1> 获得 DatabaseIdProvider 的类
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility 保持兼容
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // <2> 获得 Properties 对象
            Properties properties = context.getChildrenAsProperties();
            // <3> 创建 DatabaseIdProvider 对象，并设置对应的属性
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            // <4> 获得对应的 databaseId 编号
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            // <5> 设置到 configuration 中
            configuration.setDatabaseId(databaseId);
        }
    }

    /**
     * 解析 <transactionManager /> 标签，返回 TransactionFactory 对象。
     * @param context
     * @return
     * @throws Exception
     */
    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 TransactionFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();
            // 创建 TransactionFactory 对象，并设置属性
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    /**
     * 解析 <dataSource /> 标签，返回 DataSourceFactory 对象
     * @param context
     * @return
     * @throws Exception
     */
    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 DataSourceFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();
            // 创建 DataSourceFactory 对象，并设置属性
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     *解析 typeHandlers 标签，
     * 最后我们得到的是 JavaType 和 JdbcType，以及用来做相互映射的 TypeHandler 之间的映射关系。
     * 最后存放在 configuraion的TypeHandlerRegistry 对象里面。
     * @param parent
     */
    private void typeHandlerElement(XNode parent) {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                // <1> 如果是 package 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                    // <2> 如果是 typeHandler 标签，则注册该 typeHandler 信息
                } else {
                    // 获得 javaType、jdbcType、handler
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    // 注册 typeHandler
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            // <0> 遍历子节点
            for (XNode child : parent.getChildren()) {
                // <1> 如果是 <package /> 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    // 获得包名
                    String mapperPackage = child.getStringAttribute("name");
                    // 添加到 configuration 的MapperRegistry中
                    configuration.addMappers(mapperPackage);
                    // 如果是 <mapper /> 标签
                } else {
                    // 获得 resource、url、class 属性
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    // <2> 使用相对于类路径的资源引用
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);
                        // 获得 resource 的 InputStream 对象
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        // 创建XMLMapperBuilder：解析 Mapper映射xml配置文件的 映射器
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                        mapperParser.parse(); // XMLMapperBuilder.parse()方法，是对 Mapper 映射器的解析。里面有两个方法
                        // <3> 使用完全限定资源定位符（URL）
                    } else if (resource == null && url != null && mapperClass == null) {
                        ErrorContext.instance().resource(url);
                        // 获得 url 的 InputStream 对象
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        // 创建 XMLMapperBuilder 对象
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        // 执行解析
                        mapperParser.parse();
                        // <4> 使用映射器接口实现类的完全限定类名
                    } else if (resource == null && url == null && mapperClass != null) {
                        // 获得 Mapper 接口
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        // 添加到 configuration 的MapperRegistry中
                        configuration.addMapper(mapperInterface);
                    } else {
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

        import java.io.InputStream;
        import java.io.Reader;
        import java.util.Properties;
        import javax.sql.DataSource;

        import org.apache.ibatis.builder.BaseBuilder;
        import org.apache.ibatis.builder.BuilderException;
        import org.apache.ibatis.datasource.DataSourceFactory;
        import org.apache.ibatis.executor.ErrorContext;
        import org.apache.ibatis.executor.loader.ProxyFactory;
        import org.apache.ibatis.io.Resources;
        import org.apache.ibatis.io.VFS;
        import org.apache.ibatis.logging.Log;
        import org.apache.ibatis.mapping.DatabaseIdProvider;
        import org.apache.ibatis.mapping.Environment;
        import org.apache.ibatis.parsing.XNode;
        import org.apache.ibatis.parsing.XPathParser;
        import org.apache.ibatis.plugin.Interceptor;
        import org.apache.ibatis.reflection.DefaultReflectorFactory;
        import org.apache.ibatis.reflection.MetaClass;
        import org.apache.ibatis.reflection.ReflectorFactory;
        import org.apache.ibatis.reflection.factory.ObjectFactory;
        import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
        import org.apache.ibatis.session.AutoMappingBehavior;
        import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
        import org.apache.ibatis.session.Configuration;
        import org.apache.ibatis.session.ExecutorType;
        import org.apache.ibatis.session.LocalCacheScope;
        import org.apache.ibatis.transaction.TransactionFactory;
        import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 * 继承 BaseBuilder 抽象类，XML 配置构建器，主要负责解析 mybatis-config.xml 配置文件
 * 即对应 https://mybatis.org/mybatis-3/zh/configuration.html
 */
public class XMLConfigBuilder extends BaseBuilder {

    // 是否已解析
    private boolean parsed; // 判断全局配置文件是否被解析过，初次被构造函数初始化时是 false
    // 基于 Java XPath 解析器
    private final XPathParser parser; // 真正的对xml全局配置文件进行解析的对象
    private String environment; // 指定数据库环境，如果没有指定默认是default
    // ReflectorFactory 对象
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        // <1> 创建 Configuration 对象
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        // <2> 设置 Configuration 的 variables 属性
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    public Configuration parse() {
        // <1.1> 如果为true，说明被解析过，抛出 BuilderException 异常
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        // <1.2> 标记已解析
        parsed = true;
        // 解析全局配置文件，从全局配置文件的configuration根标签开始解析
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    private void parseConfiguration(XNode root) {
        try {
            //issue #117 read properties first
            // 解析properties标签，解析成properties对象，并保存到XPathParser 和 Configuration 的 variables变量中
            propertiesElement(root.evalNode("properties"));
            // 解析settings标签，把<settings>标签也解析成了一个 Properties 对象，对于<settings>标签的子标签的处理在后面
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            // 加载自定义 VFS 实现类
            loadCustomVfs(settings);// loadCustomVfs 是获取 Vitual File System 的自定义实现类
            loadCustomLogImpl(settings); // loadCustomLogImpl 是根据<logImpl>标签获取日志的实现类，我们可以用到很多的日志的方案，包括 LOG4J，LOG4J2，SLF4J 等等。这里生成了一个 Log 接口的实现类，并且赋值到 Configuration 中。
            // 解析typeAliases标签，把<类的别名和类的关系，我们保存到Configuration 的 TypeAliasRegistry 对象里面。
            typeAliasesElement(root.evalNode("typeAliases"));
            // 接下来就是解析<plugins>标签，比如 Pagehelper 的翻页插件，或者我们自定义的插件。
            // 【<plugins>标签里面只有<plugin>标签，<plugin>标签里面只有<property>标签】。
            //标签解析完以后，会生成一个 Interceptor 对象，并且添加到 Configuration 的InterceptorChain 属性里面，InterceptorChain只有一个属性——List<Interceptor>。
            pluginElement(root.evalNode("plugins"));
            // 接 下 来 的 两 个 标 签 是 用 来 实 例 化 对 象 用 的 ， <objectFactory> 和
            //<objectWrapperFactory> 这 两 个 标 签 ， 分 别 生 成 ObjectFactory 、
            //ObjectWrapperFactory 对象，同样设置到 Configuration 的属性里面。
            objectFactoryElement(root.evalNode("objectFactory"));
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
            // 解析 reflectorFactory 标签，生成 ReflectorFactory 对象（在官方 3.5.1 的 pdf 文档里面没有找到这个配置）。
            reflectorFactoryElement(root.evalNode("reflectorFactory"));
            // 二级标签里面有很多的配置，比如二级缓存，延迟加载，自动生成主键这些。需要注意的是，我们之前提到的所有的默认值，都是在这里赋值的。
            //所有的值，都会赋值到 Configuration 的属性里面去。
            settingsElement(settings);
            // 解析<environments>标签
            // 一个 environment 就是对应一个数据源，所以在这里我们会根据配置的<transactionManager>创建一个事务工厂，
            // 根据<dataSource>标签创建一个数据源，最后把这两个对象设置成 Environment 对象的属性，放到 Configuration 里面。
            environmentsElement(root.evalNode("environments"));
            // 解析 databaseIdProvider 标签，生成 DatabaseIdProvider 对象（用来支持不同厂商的数据库）。
            // 参考https://mybatis.org/mybatis-3/zh/configuration.html#databaseIdProvider
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));
            // 解析 typeHandlers 标签，
            // 最后我们得到的是 JavaType 和 JdbcType，以及用来做相互映射的 TypeHandler 之间的映射关系。
            // 最后存放在 configuraion的TypeHandlerRegistry 对象里面。
            typeHandlerElement(root.evalNode("typeHandlers"));
            // <mappers>标签的解析。
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    /**
     * 将 <setting /> 标签解析为 Properties 对象。
     * @param context
     * @return
     */
    private Properties settingsAsProperties(XNode context) {
        // 如果setting为空（没有设置）返回一个空的 Properties 对象
        if (context == null) {
            return new Properties();
        }
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        // 校验每个属性，在 Configuration 中，有相应的 setting 方法，否则抛出 BuilderException 异常
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    /**
     * 加载自定义 VFS 实现类
     * @param props
     * @throws ClassNotFoundException
     */
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        // 获得 vfsImpl 属性
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            // 使用 , 作为分隔符，拆成 VFS 类名的数组
            String[] clazzes = value.split(",");
            // 遍历 VFS 类名的数组
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    // 获得 VFS 类
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
                    // 设置到 Configuration 中
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    private void loadCustomLogImpl(Properties props) {
        Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
    }

    /**
     * 解析typeAliases标签，把<类的别名和类的关系，我们保存到Configuration 的 TypeAliasRegistry 对象里面。
     * @param parent
     */
    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                // 指定为包的情况下，注册包下的每个类
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                } else { // 指定为类的情况下，直接注册类和别名
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type); // 获得类是否存在
                        // 注册到 typeAliasRegistry 中
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) { // 若类不存在，则抛出 BuilderException 异常
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * 接下来就是解析<plugins>标签，比如 Pagehelper 的翻页插件，或者我们自定义的插件。
     * 【<plugins>标签里面只有<plugin>标签，<plugin>标签里面只有<property>标签】。
     *标签解析完以后，会生成一个 Interceptor 对象，并且添加到 Configuration 的InterceptorChain 属性里面，InterceptorChain只有一个属性——List<Interceptor>。
     * @param parent
     * @throws Exception
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            // 遍历 <plugins /> 标签
            for (XNode child : parent.getChildren()) {
                String interceptor = child.getStringAttribute("interceptor");
                Properties properties = child.getChildrenAsProperties();
                // <1> 创建 Interceptor 对象，并设置属性
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);
                // <2> 添加到 configuration 中
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ObjectFactory 的实现类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties properties = context.getChildrenAsProperties();
            // <1> 创建 ObjectFactory 对象，并设置 Properties 属性
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            factory.setProperties(properties);
            // <2> 设置 Configuration 的 objectFactory 属性
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // // 获得 ObjectWrapperFactory 的实现类
            String type = context.getStringAttribute("type");
            // <1> 创建 ObjectWrapperFactory 对象
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 objectWrapperFactory 属性
            configuration.setObjectWrapperFactory(factory);
        }
    }

    /**
     * 解析 reflectorFactory 标签，生成 ReflectorFactory 对象
     * @param context
     * @throws Exception
     */
    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 ReflectorFactory 的实现类
            String type = context.getStringAttribute("type");
            // 创建 ReflectorFactory 对象
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            // 设置 Configuration 的 reflectorFactory 属性
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     *
     * @param context
     * @throws Exception
     * 解析 <properties /> 节点。大体逻辑如下：
     * 1 解析 <properties /> 标签，成 Properties 对象。
     * 2 覆盖 configuration 中的 Properties 对象到上面的结果。
     * 3 设置结果到 parser 和 configuration 中。
     */
    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 读取properties子标签们，为 Properties 对象
            Properties defaults = context.getChildrenAsProperties();
            // 读取 resource 和 url 属性
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");
            if (resource != null && url != null) { // resource 和 url 都存在的情况下，抛出 BuilderException 异常
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            // 读取本地 Properties 配置文件到 defaults 中。
            if (resource != null) {
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) { // 读取远程 Properties 配置文件到 defaults 中
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            // 覆盖 configuration 中的 Properties 对象到 defaults 中。
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            // 设置 defaults 到 parser 和 configuration 中。
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    private void settingsElement(Properties props) {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * 解析<environments>标签
     * 一个 environment 就是对应一个数据源，所以在这里我们会根据配置的<transactionManager>创建一个事务工厂，
     * 根据<dataSource>标签创建一个数据源，最后把这两个对象设置成 Environment 对象的属性，放到 Configuration 里面。
     * @param context
     * @throws Exception
     */
    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            // <1> environment 属性非空，从 default 属性获得
            if (environment == null) { // 如果environment，则使用默认"default"数据源的environment标签的id
                environment = context.getStringAttribute("default");
            }
            // 遍历 XNode 节点
            for (XNode child : context.getChildren()) {
                String id = child.getStringAttribute("id");
                // <2> 判断 environment 是否匹配
                if (isSpecifiedEnvironment(id)) { // 如果匹配到environment的id
                    // 创建事务工厂 <3> 解析 `<transactionManager />` 标签，返回 TransactionFactory 对象
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    // 创建数据源工厂 解析 `<dataSource />` 标签，返回 DataSourceFactory 对象
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    // 获取数据源
                    DataSource dataSource = dsFactory.getDataSource();
                    // 构建Environment对象，并且保存到configuration的Environment对象中
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    // <6> 构造 Environment 对象，并设置到 configuration 中
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     *解析 databaseIdProvider 标签，生成 DatabaseIdProvider 对象（用来支持不同厂商的数据库）。
     * 参考https://mybatis.org/mybatis-3/zh/configuration.html#databaseIdProvider
     * @param context
     * @throws Exception
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            // <1> 获得 DatabaseIdProvider 的类
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility 保持兼容
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // <2> 获得 Properties 对象
            Properties properties = context.getChildrenAsProperties();
            // <3> 创建 DatabaseIdProvider 对象，并设置对应的属性
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            // <4> 获得对应的 databaseId 编号
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            // <5> 设置到 configuration 中
            configuration.setDatabaseId(databaseId);
        }
    }

    /**
     * 解析 <transactionManager /> 标签，返回 TransactionFactory 对象。
     * @param context
     * @return
     * @throws Exception
     */
    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 TransactionFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();
            // 创建 TransactionFactory 对象，并设置属性
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    /**
     * 解析 <dataSource /> 标签，返回 DataSourceFactory 对象
     * @param context
     * @return
     * @throws Exception
     */
    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // 获得 DataSourceFactory 的类
            String type = context.getStringAttribute("type");
            // 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();
            // 创建 DataSourceFactory 对象，并设置属性
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     *解析 typeHandlers 标签，
     * 最后我们得到的是 JavaType 和 JdbcType，以及用来做相互映射的 TypeHandler 之间的映射关系。
     * 最后存放在 configuraion的TypeHandlerRegistry 对象里面。
     * @param parent
     */
    private void typeHandlerElement(XNode parent) {
        if (parent != null) {
            // 遍历子节点
            for (XNode child : parent.getChildren()) {
                // <1> 如果是 package 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                    // <2> 如果是 typeHandler 标签，则注册该 typeHandler 信息
                } else {
                    // 获得 javaType、jdbcType、handler
                    String javaTypeName = child.getStringAttribute("javaType");
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    // 注册 typeHandler
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            // <0> 遍历子节点
            for (XNode child : parent.getChildren()) {
                // <1> 如果是 <package /> 标签，则扫描该包
                if ("package".equals(child.getName())) {
                    // 获得包名
                    String mapperPackage = child.getStringAttribute("name");
                    // 添加到 configuration 的MapperRegistry中
                    configuration.addMappers(mapperPackage);
                    // 如果是 <mapper /> 标签
                } else {
                    // 获得 resource、url、class 属性
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    // <2> 使用相对于类路径的资源引用
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);
                        // 获得 resource 的 InputStream 对象
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        // 创建XMLMapperBuilder：解析 Mapper映射xml配置文件的 映射器
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                        mapperParser.parse(); // XMLMapperBuilder.parse()方法，是对 Mapper 映射器的解析。里面有两个方法
                        // <3> 使用完全限定资源定位符（URL）
                    } else if (resource == null && url != null && mapperClass == null) {
                        ErrorContext.instance().resource(url);
                        // 获得 url 的 InputStream 对象
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        // 创建 XMLMapperBuilder 对象
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        // 执行解析
                        mapperParser.parse();
                        // <4> 使用映射器接口实现类的完全限定类名
                    } else if (resource == null && url == null && mapperClass != null) {
                        // 获得 Mapper 接口
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        // 添加到 configuration 的MapperRegistry中
                        configuration.addMapper(mapperInterface);
                    } else {
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
