# Java后端+AI工程化 面试知识框架
Java 后端 + AI 工程化方向面试知识大纲 · 按优先级从高到低组织
本文档覆盖 Java 后端与 AI 工程化方向的核心面试知识点，按优先级从高到低组织：P0 必会 → P1 高频 → P2 差异化 AI 工程化 → P3 锦上添花，最后为项目梳理要点。建议按 P0 → P1 → P2 的顺序系统复习，P3 与项目梳理用于临场差异化表达。
目录
P0 必会（最高优先级）
P1 高频（高优先级）
P2 差异化 AI 工程化（简历有 AI 项目重点）
P3 锦上添花
项目梳理
## P0 必会（最高优先级）
基础能力是面试的地基，任何岗位都会重点考察，建议优先且反复复习。
### Java基础与并发
涵盖 Java 语言基础与多线程并发两大核心板块。
#### 基础语法
数据类型、包装类、拆装箱
String、StringBuilder/StringBuffer
面向对象：继承多态、接口抽象类
集合框架
List：ArrayList、LinkedList
Map：HashMap、ConcurrentHashMap
Set、Queue、并发集合
异常、IO/NIO/AIO
泛型、反射、注解、Lambda、Stream
#### 多线程并发
线程状态、创建方式
synchronized、Lock、AQS
volatile、内存可见性、指令重排
线程池 ThreadPoolExecutor、拒绝策略
CAS、ABA、CountDownLatch、Semaphore
### JVM虚拟机
内存区域划分：堆、栈、元空间
对象创建、内存布局
GC：存活判断、GC 算法、G1/ZGC
JVM 参数、OOM 场景、内存泄漏排查
类加载、双亲委派模型
### MySQL
InnoDB、B+树、聚簇/非聚簇索引
ACID、隔离级别、MVCC
SQL 优化、explain、索引失效
锁：行锁、表锁、意向锁、死锁
分库分表、慢查询
### Redis
5 种数据结构、底层编码
RDB/AOF 持久化、过期、内存淘汰
缓存穿透/击穿/雪崩
分布式锁
主从、哨兵、Redis-Cluster
### 计算机网络
HTTP1.1/2/3、HTTPS TLS 握手
TCP 三次握手四次挥手、滑动窗口、拥塞控制
Socket、长/短连接、WebSocket
GET/POST、HTTP 状态码
### 操作系统
进程线程区别
内存分页
IO 模型 BIO/NIO/AIO
## P1 高频（高优先级）
围绕 Spring 技术栈、常用中间件与工程实践展开，是后端岗位的高频考点。
### Spring 全家桶
Spring 核心 + Boot + MVC + Cloud 全链路。
#### Spring 核心
IOC、Bean 生命周期、Bean 作用域
AOP、JDK 动态代理/CGLIB
@Transactional：传播、隔离、事务失效场景
#### SpringBoot
自动装配、starter 机制
配置加载、条件注解
启动流程、全局异常、拦截器过滤器
#### SpringMVC
请求流程、参数解析
#### SpringCloud 微服务
Nacos、Feign、Gateway、Sentinel
### 中间件
消息队列与搜索引擎两大常用中间件。
#### MQ（Kafka/RocketMQ）
消息可靠性、重复消费、丢失、积压
分区、offset
#### Elasticsearch
倒排索引、分词、调优
### 算法与工程实践
LeetCode：数组、链表、哈希、双指针、树、DP
设计模式：单例、工厂、策略、模板、观察者
系统设计
接口幂等、分布式 ID
限流熔断降级
秒杀、短链接
线上排查：CPU 高、内存飙升、接口慢
## P2 差异化 AI 工程化（简历有 AI 项目重点）
具备 AI 项目经验的候选人重点准备，用于体现差异化竞争力。
### LLM 基础
主流大模型 API
Token、上下文窗口、温度 top-p
### Prompt 工程
角色、少样本、思维链、结构化输出
幻觉缓解方案
### RAG 检索增强
完整链路：文档解析→分块→向量化→检索→生成
分块策略、多路召回、Rerank 重排
RAG 问题：幻觉、召回不准优化
### 向量数据库
Qdrant/Milvus、向量索引、相似度检索
向量库 vs 传统数据库
### Agent 智能体
LangChain/LangGraph
规划、记忆、Function-call 工具调用
### AI 后端工程
SSE 流式输出、WebSocket
Token 限流、超时重试、上下文管理
大模型结果缓存、成本控制
### Java 集成 AI
Java 调用 LLM SDK、Java 对接向量库、RAG 后端开发
## P3 锦上添花
Docker 容器
分布式系统理论
## 项目梳理
传统 Java 后端项目：业务、难点、优化
AI 项目：RAG/Agent，选型、难点、问题解决
