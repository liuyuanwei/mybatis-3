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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 * 继承 BaseBuilder 抽象类，Mapper XML 配置构建器，主要负责解析 Mapper 映射xml配置文件。
 */
public class XMLMapperBuilder extends BaseBuilder {

    /**
     * 基于 Java XPath 解析器
     */
    private final XPathParser parser;
    /**
     * Mapper 构造器助手
     */
    private final MapperBuilderAssistant builderAssistant;
    /**
     * 可被其他语句引用的可重用语句块的集合
     *
     * 例如：<sql id="userColumns"> ${alias}.id,${alias}.username,${alias}.password </sql>
     */
    private final Map<String, XNode> sqlFragments;
    /**
     * 资源引用的地址
     */
    private final String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        super(configuration);
        // 创建 MapperBuilderAssistant 对象
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    public void parse() {
        // 校验这个mapper映射文件是否解析过，解析过不再进行解析
        if (!configuration.isResourceLoaded(resource)) {
            // <2> 解析 `<mapper />` 节点
            //  解 析 所 有 的 子 标 签 ， 其 中buildStatementFromContext()最终获得 MappedStatement 对象并保存到configuration对象中。
            //  保存在configuration是以Map的形式保存，key是ms.getId()——即是statementId
            configurationElement(parser.evalNode("/mapper"));
            // 解析完成后添加映射文件到configuration中,标记该 Mapper 已经加载过
            configuration.addLoadedResource(resource);
            // <4> 绑定 Mapper
            // 把 namespace（接口类型）和接口的工厂类绑定起来。
            bindMapperForNamespace();
        }

        /**
         * 三个方法的逻辑思路基本一致：1）获得对应的集合；2）遍历集合，执行解析；3）执行成功，则移除出集合；
         * 4）执行失败，忽略异常。
         * 当然，实际上，此处还是可能有执行解析失败的情况，但是随着每一个 Mapper 映射xml配置文件对应的 XMLMapperBuilder
         * 执行一次这些方法，逐步逐步就会被全部解析完
         */
        // <5> 解析待定的 <resultMap /> 节点
        parsePendingResultMaps();
        // <6> 解析待定的 <cache-ref /> 节点
        parsePendingCacheRefs();
        // <7> 解析待定的 SQL 语句的节点
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    /**
     * 解析 `<mapper />` 节点
     * @param context
     */
    private void configurationElement(XNode context) {
        try {
            // <1> 获得 namespace 属性
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.equals("")) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            // <1> 设置 namespace 属性
            builderAssistant.setCurrentNamespace(namespace); // builderAssistant设置namespace
            // <2> 解析 <cache-ref /> 节点
            cacheRefElement(context.evalNode("cache-ref"));
            // <3> 解析 <cache /> 节点
            // 解析cache标签，会创建Cache装饰器对象并保存到builderAssistant对象中
            cacheElement(context.evalNode("cache"));
            // 【已废弃！】老式风格的参数映射。内联参数是首选,这个元素可能在将来被移除，这里不会记录
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            // <4> 解析 <resultMap /> 节点们
            resultMapElements(context.evalNodes("/mapper/resultMap"));
            // <5> 解析 <sql /> 节点们
            sqlElement(context.evalNodes("/mapper/sql"));
            // <6> 解析 <select /> <insert /> <update /> <delete /> 节点们
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
        }
    }

    /**
     * 解析 <select />、<insert />、<update />、<delete /> 节点们。
     * @param list
     */
    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            buildStatementFromContext(list, configuration.getDatabaseId());
        }
        buildStatementFromContext(list, null);
        // 上面两块代码，可以简写成 buildStatementFromContext(list, configuration.getDatabaseId());
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        // <1> 遍历 <select /> <insert /> <update /> <delete /> 节点们
        for (XNode context : list) {
            // <1> 创建 XMLStatementBuilder 对象，执行解析
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                // 解析
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                // <2> 解析失败，添加到 configuration 中
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        // 从Configuration中获得 ResultMapResolver 集合，并遍历进行处理
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    // 执行解析
                    iter.next().resolve();
                    // 移除
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                    // 解析失败，不抛出异常
                }
            }
        }
    }

    private void parsePendingCacheRefs() {
        // 从Configuration中获得 CacheRefResolver 集合，并遍历进行处理
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    // 执行解析
                    iter.next().resolveCacheRef();
                    // 移除
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                    // 解析失败，不抛出异常
                }
            }
        }
    }

    private void parsePendingStatements() {
        // 从Configuration中获得 XMLStatementBuilder 集合，并遍历进行处理
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    // 执行解析
                    iter.next().parseStatementNode();
                    // 移除
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                    // 解析失败，不抛出异常
                }
            }
        }
    }

    /**
     * 解析 <cache-ref /> 节点
     * 示例：<cache-ref namespace="com.someone.application.data.SomeMapper"/>
     * 方法中没有存在遍历，说明mapper映射xml文件只能有一个<cache-ref />标签
     * @param context
     */
    private void cacheRefElement(XNode context) {
        if (context != null) {
            // <1> 获得指向的 namespace 名字，并添加到 configuration 的 cacheRefMap 中
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            // <2> 创建 CacheRefResolver 对象，并执行解析
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                // <3> 解析失败，添加到 configuration 的 incompleteCacheRefs 中
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

  /*
    // 使用默认缓存
    <cache eviction="FIFO" flushInterval="60000"  size="512" readOnly="true"/>

    // 使用自定义缓存
    <cache type="com.domain.something.MyCustomCache">
      <property name="cacheFile" value="/tmp/my-custom-cache.tmp"/>
    </cache>
   */
    /**
     * 声明这个 namespace 使用二级缓存
     * <cache  type ="org.apache.ibatis.cache.impl.PerpetualCache"
     * size ="1024" <!—最多缓存对象个数，默认 1024-->
     * eviction ="LRU" <!—回收策略-->
     * flushInterval ="120000" <!—自动刷新时间 ms，未配置时只有调用时刷新-->
     * readOnly =" false" "/> <!— 默认是  false（安全），改为 true 可读写时，对象必须支持序列
     * 化 -->
     */
    /**
     * <3> 解析 <cache /> 节点
     * @param context
     */
    private void cacheElement(XNode context) {
        if (context != null) {
            // <1> 获得负责存储的 Cache 实现类
            String type = context.getStringAttribute("type", "PERPETUAL");
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
            // <2> 获得负责过期的 Cache 实现类
            String eviction = context.getStringAttribute("eviction", "LRU");
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
            // <3> 获得 flushInterval、size、readWrite、blocking 属性
            Long flushInterval = context.getLongAttribute("flushInterval");
            Integer size = context.getIntAttribute("size");
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);
            boolean blocking = context.getBooleanAttribute("blocking", false);
            // <4> 获得 Properties 属性
            Properties props = context.getChildrenAsProperties();
            // <5> 创建 Cache 对象
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    private void parameterMapElement(List<XNode> list) {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    /**
     * 解析 <resultMap /> 节点们
     * @param list
     * @throws Exception
     */
    private void resultMapElements(List<XNode> list) throws Exception {
        // 遍历 <resultMap /> 节点们
        for (XNode resultMapNode : list) {
            try {
                // 处理单个 <resultMap /> 节点
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    // 处理单个 <resultMap /> 节点
    private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
        return resultMapElement(resultMapNode, Collections.emptyList(), null);
    }

    // 处理单个 <resultMap /> 节点
    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) throws Exception {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        // <1> 获得 type 属性
        String type = resultMapNode.getStringAttribute("type",
                resultMapNode.getStringAttribute("ofType",
                        resultMapNode.getStringAttribute("resultType",
                                resultMapNode.getStringAttribute("javaType"))));
        // <1> 解析 type 对应的类
        Class<?> typeClass = resolveClass(type);
        if (typeClass == null) {
            typeClass = inheritEnclosingType(resultMapNode, enclosingType);
        }
        Discriminator discriminator = null;
        // <2> 创建 ResultMapping 集合
        // 后遍历 <resultMap /> 的子节点们，将每一个子节点解析成一个或多个 ResultMapping 对象，添加到集合中
        List<ResultMapping> resultMappings = new ArrayList<>();
        resultMappings.addAll(additionalResultMappings);
        // <2> 遍历 <resultMap /> 的子节点
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            // <2.1> 处理 <constructor /> 节点
            if ("constructor".equals(resultChild.getName())) {
                processConstructorElement(resultChild, typeClass, resultMappings);
                // <2.2> 处理 <discriminator /> 节点
            } else if ("discriminator".equals(resultChild.getName())) {
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
                // <2.3> 处理其它节点
            } else {
                List<ResultFlag> flags = new ArrayList<>();
                if ("id".equals(resultChild.getName())) {
                    flags.add(ResultFlag.ID);
                }
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        // <1> 获得 id 属性
        String id = resultMapNode.getStringAttribute("id",
                resultMapNode.getValueBasedIdentifier());
        // <1> 获得 extends 属性
        String extend = resultMapNode.getStringAttribute("extends");
        // <1> 获得 autoMapping 属性
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
        // <3> 创建 ResultMapResolver 对象，执行解析
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            return resultMapResolver.resolve();
        } catch (IncompleteElementException  e) {
            // <4> 解析失败，添加到 configuration 中
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
        if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            String property = resultMapNode.getStringAttribute("property");
            if (property != null && enclosingType != null) {
                MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
                return metaResultType.getSetterType(property);
            }
        } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            return enclosingType;
        }
        return null;
    }

    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                flags.add(ResultFlag.ID);
            }
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Map<String, String> discriminatorMap = new HashMap<>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
            discriminatorMap.put(value, resultMap);
        }
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    /**
     * 解析 <sql /> 节点们
     * @param list
     */
    private void sqlElement(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            sqlElement(list, configuration.getDatabaseId());
        }
        sqlElement(list, null);
        // 上面两块代码，可以简写成 sqlElement(list, configuration.getDatabaseId());
    }

    private void sqlElement(List<XNode> list, String requiredDatabaseId) {
        // <1> 遍历所有 <sql /> 节点
        for (XNode context : list) {
            // <2> 获得 databaseId 属性
            String databaseId = context.getStringAttribute("databaseId");
            // <3> 获得完整的 id 属性，格式为 `${namespace}.${id}`
            String id = context.getStringAttribute("id");
            id = builderAssistant.applyCurrentNamespace(id, false);
            // <4> 判断 databaseId 是否匹配
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                // <5> 添加到 sqlFragments 中
                sqlFragments.put(id, context);
            }
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            // 如果不匹配，则返回 false
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            // 如果未设置 requiredDatabaseId ，但是 databaseId 存在，说明还是不匹配，则返回 false
            if (databaseId != null) {
                return false;
            }
            // skip this fragment if there is a previous one with a not null databaseId
            // 判断是否已经存在
            if (this.sqlFragments.containsKey(id)) {
                XNode context = this.sqlFragments.get(id);
                // 若存在，则判断原有的 sqlFragment 是否 databaseId 为空。因为，当前 databaseId 为空，这样两者才能匹配
                if (context.getStringAttribute("databaseId") != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
        String property;
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            property = context.getStringAttribute("name");
        } else {
            property = context.getStringAttribute("property");
        }
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        String nestedResultMap = context.getStringAttribute("resultMap",
                processNestedResultMappings(context, Collections.emptyList(), resultType));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) throws Exception {
        if ("association".equals(context.getName())
                || "collection".equals(context.getName())
                || "case".equals(context.getName())) {
            if (context.getStringAttribute("select") == null) {
                validateCollection(context, enclosingType);
                ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
                return resultMap.getId();
            }
        }
        return null;
    }

    protected void validateCollection(XNode context, Class<?> enclosingType) {
        if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
                && context.getStringAttribute("javaType") == null) {
            MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
            String property = context.getStringAttribute("property");
            if (!metaResultType.hasSetter(property)) {
                throw new BuilderException(
                        "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
            }
        }
    }

    private void bindMapperForNamespace() {
        // <1> 获得 Mapper 映射配置文件对应的 Mapper 接口，实际上类名就是 namespace 。嘿嘿，这个是常识。
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                // 获取namespace所指定的类
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                // <2> 不存在该 Mapper 接口，则进行添加
                if (!configuration.hasMapper(boundType)) {
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource
                    // <3> 标记 namespace 已经添加，避免 MapperAnnotationBuilder#loadXmlResource(...) 重复加载
                    configuration.addLoadedResource("namespace:" + namespace);
                    // 把Mapper接口类和它的接口的代理工厂类进行Map映射绑定起来并 添加到 configuration 中
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}
