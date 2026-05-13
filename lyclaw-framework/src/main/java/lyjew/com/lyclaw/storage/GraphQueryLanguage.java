package lyjew.com.lyclaw.storage;

/**
 * 图查询语言（Graph Query Language）枚举，定义了框架知识图谱存储中支持的图数据库查询语言类型。
 *
 * <p>在 LyClaw 框架的高级记忆和知识管理场景中，知识图谱（Knowledge Graph）用于存储实体、
 * 关系和属性，形成结构化的知识网络，支持复杂的关系推理和知识发现。不同的图数据库
 * 系统使用不同的查询语言来操作图数据，本枚举定义了框架适配支持的主要图查询语言，
 * 框架根据此枚举选择对应的查询构造器和结果解析器。
 *
 * <p>支持的四种图查询语言：
 * <ul>
 *   <li><b>CYPHER</b>：Neo4j 图数据库的原生查询语言，也是目前使用最广泛的属性图查询语言。
 *       Cypher 采用 ASCII 艺术风格的声明式语法，使用节点用圆括号 (n)、关系用方括号箭头
 *       -[:RELATES]-> 表示，直观易读。2015 年被 openCypher 项目标准化，现已成为图查询
 *       领域的工业标准。适用于 Neo4j 及兼容 openCypher 的图数据库（如 Memgraph、
 *       AgensGraph 等）。示例：{@code MATCH (n:Person)-[:KNOWS]->(m:Person) RETURN n, m}</li>
 *   <li><b>SQL_CTE</b>：使用 SQL 的公共表表达式（Common Table Expression，CTE）和递归
 *       查询来实现图遍历，适用于基于关系型数据库的图存储方案。通过在 SQL 中使用
 *       WITH RECURSIVE 子句定义递归查询，可以利用已有的关系型数据库基础设施实现
 *       图查询能力，无需额外部署专用图数据库。适合数据量中等、不想引入新数据库组件
 *       的场景。实现依赖于支持 SQL:1999 递归 CTE 标准的关系型数据库（如 PostgreSQL、
 *       MySQL 8.0+、SQLite 3.8.3+）</li>
 *   <li><b>GREMLIN</b>：Apache TinkerPop 图计算框架的遍历语言，是图查询领域的函数式
 *       编程风格语言。Gremlin 采用链式遍历（Traversal）的模式，通过一系列步骤
 *       （如 V()、outE()、inV()、has()、values()）组合成图遍历管道，表达能力强且
 *       适合复杂的图算法。与声明式的 Cypher 不同，Gremlin 是命令式的，对开发者而言
 *       更具编程灵活性。适用于 Apache TinkerPop 生态中的图数据库（如 JanusGraph、
 *       Amazon Neptune、Azure Cosmos DB 等）</li>
 *   <li><b>SPARQL</b>：W3C 标准的 RDF（Resource Description Framework）图查询语言，
 *       用于查询和操作以 RDF 三元组（主语-谓词-宾语）形式存储的图数据。SPARQL 采用
 *       SQL 类似的 SELECT-WHERE 语法，但专门针对图模式匹配进行了设计。适用于基于
 *       RDF 标准的图数据库和三元组存储（如 Apache Jena、RDF4J、Virtuoso、GraphDB
 *       等），是语义网（Semantic Web）和链接数据（Linked Data）生态的核心技术</li>
 * </ul>
 *
 * <p>每种查询语言对应不同的图存储后端实现，框架根据配置的 GraphQueryLanguage 类型
 * 自动选择对应的查询方言构造器和结果映射器。在知识图谱存储配置中指定此枚举值来确定
 * 使用哪种图查询语言。
 */
public enum GraphQueryLanguage {
    CYPHER,
    SQL_CTE,
    GREMLIN,
    SPARQL
}
