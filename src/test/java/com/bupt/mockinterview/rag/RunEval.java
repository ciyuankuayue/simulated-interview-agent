package com.bupt.mockinterview.rag;

import okhttp3.OkHttpClient;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

/**
 * RAG 检索评测（规模化版）：真实 DashscopeEmbedder + 真实 LuceneVectorStore。
 * 语料：15 主题 ×（1 目标文档 + 9 同主题干扰文档）= 150 篇；每主题 2 条查询，共 30 条。
 * 目标文档含一个仅它独有的技术签名词（如「间隙锁」「Remembered Set」「ReadView」），
 * 干扰文档是同领域近义内容但不含该词 —— 用于暴露纯向量在「近义干扰」下的召回下滑，
 * 验证 BM25 / 混合检索的补偿作用。
 * 运行：.\mvnw.cmd test "-Dtest=RunEval"  （需环境变量 DASHSCOPE_API_KEY）
 */
public class RunEval {

    private static final String MODEL = "qwen3.7-text-embedding-flash";
    private static final String BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    // 每行：签名词, 目标文档, 9 个同主题干扰文档
    private static final String[][] TOPICS = {
            {"间隙锁",
                    "InnoDB 的间隙锁 Gap Lock 锁定索引记录之间的间隙，防止其他事务在间隙中插入数据，从而解决幻读问题，它与记录锁组合成 Next-Key Lock 在可重复读级别默认生效。",
                    "InnoDB 的记录锁锁定具体的索引行，只有走索引才会加行锁否则退化为表锁。",
                    "Next-Key Lock 是记录锁加间隙锁的组合，在可重复读隔离级别下默认生效防止幻读。",
                    "意向锁是表级锁，告知数据库即将在行上加何种锁，分为意向共享锁和意向排他锁。",
                    "插入意向锁是一种特殊的间隙锁，多个事务向同一间隙不同位置插入不会相互互斥。",
                    "InnoDB 的自增锁保证自增列递增，在并发插入时通过轻量级互斥控制避免冲突。",
                    "临键锁通过锁定左开右闭的区间避免幻读，是 RR 级别下的默认加锁方式。",
                    "行锁必须建立在索引之上，全表扫描的更新会升级为表级锁阻塞其他写入。",
                    "死锁检测会主动回滚代价较小的事务，释放锁让其他事务继续推进。",
                    "锁等待超时参数控制事务等待行锁的最长时间，超时后抛出锁等待异常。"},
            {"Remembered Set",
                    "G1 垃圾回收器用 Remembered Set 记录跨 Region 的引用关系，避免回收时全堆扫描，写屏障在引用赋值时维护 RSet 的卡表。",
                    "G1 将堆划分为多个大小相等的 Region，优先回收回收价值最高的区域。",
                    "G1 的 Mixed GC 同时回收年轻代和部分老年代 Region，目标是控制停顿时间。",
                    "G1 使用 SATB 快照机制在并发标记阶段记录存活对象集合。",
                    "写屏障在每次引用赋值时更新 Remembered Set 的卡表记录跨代引用。",
                    "G1 的老年代巨型对象单独分配在连续的 Humongous Region。",
                    "G1 通过停顿预测模型动态决定每次回收的 Region 数量。",
                    "G1 支持字符串去重以减少重复字符串带来的内存占用。",
                    "G1 的并发标记与用户线程同时运行，只在初始和最终标记阶段短暂停顿。",
                    "G1 年轻代回收采用复制算法把存活对象移到 Survivor 区。"},
            {"ReadView",
                    "InnoDB 的 MVCC 用 ReadView 判断行的某个历史版本对当前事务是否可见，通过比较事务 ID 与 ReadView 的上下界决定可见性。",
                    "MVCC 通过 undo 日志维护数据的历史版本，构成版本链供快照读使用。",
                    "快照读是不加锁的一致性读，读取的是事务开始时的数据快照。",
                    "当前读会读取最新版本并对记录加锁，用于 update 和 select for update。",
                    "事务 ID 隐藏在每行记录中，用于标识版本的先后顺序。",
                    "可重复读隔离级别下同一事务内的多次快照读看到相同的数据。",
                    "幻读指同一事务两次查询返回的行数不同，由插入或删除造成。",
                    "隔离级别决定事务之间的可见性边界，包括读已提交和可重复读。",
                    "purge 线程负责清理不再被任何事务需要的旧版本数据。",
                    "InnoDB 通过一致性视图算法保证读已提交下的语句级一致性。"},
            {"跳表",
                    "Redis 的 zset 有序集合底层采用跳表 skiplist 实现范围查询，同时用字典以 O(1) 定位成员的分数。",
                    "Redis 的压缩列表 ziplist 在元素少且小时用作列表和哈希的底层结构。",
                    "Redis 的字典采用链式哈希，配合渐进式 rehash 避免单次迁移卡顿。",
                    "Redis 的哈希表在扩容时通过渐进式 rehash 分多次完成迁移。",
                    "整数集合 intset 是集合元素全为整数时的紧凑底层编码。",
                    "快速列表 quicklist 是列表的底层结构，结合 ziplist 与链表。",
                    "紧凑列表 listpack 是 Redis 新版的更省内存的序列化结构。",
                    "基数树为 Redis 的 Stream 和集群槽位提供紧凑的前缀压缩存储。",
                    "Redis 的地理索引基于有序集合与 GeoHash 编码实现附近点查询。",
                    "Redis 的 HyperLogLog 用极少内存估算集合基数。"},
            {"双亲委派",
                    "Java 的类加载双亲委派模型要求加载请求先委派给父加载器，父无法完成才由子加载器尝试，保证核心类不被篡改。",
                    "引导类加载器负责加载核心类库，由虚拟机实现且不可直接引用。",
                    "扩展类加载器加载标准扩展目录下的类，是平台的一部分。",
                    "应用类加载器加载用户类路径上的类，也称系统类加载器。",
                    "自定义类加载器可重载 findClass 实现从非标准来源加载字节码。",
                    "每个类加载器有独立的命名空间，同名类由不同加载器加载视为不同类。",
                    "双亲委派保证了类的唯一性，避免核心类被用户类冒名替换。",
                    "Tomcat 打破双亲委派让 Web 应用优先加载自身类实现模块隔离。",
                    "线程上下文类加载器可在执行时临时切换加载器加载 SPI 实现。",
                    "JDK 9 之后的模块系统对类加载的可见性施加了更严格的边界。"},
            {"AQS",
                    "Java 的 AQS 抽象队列同步器用 volatile 的 state 表示同步状态，通过 CLH 队列管理等待线程，独占与共享模式分别实现 tryAcquire。",
                    "ReentrantLock 基于 AQS 实现，支持可中断锁、公平锁与多条件变量。",
                    "Semaphore 基于 AQS 控制同时访问资源的线程数量。",
                    "CountDownLatch 基于 AQS 实现线程间的倒计时等待。",
                    "CyclicBarrier 让一组线程互相等待到达公共屏障点再继续。",
                    "Phaser 是可复用的同步屏障，支持动态调整注册方数量。",
                    "ReentrantLock 的可重入指同一线程可重复获取已持有的锁。",
                    "公平锁按请求顺序授予锁，避免线程饥饿但吞吐更低。",
                    "条件变量 Condition 配合锁实现线程的等待与唤醒。",
                    "自旋锁在获取锁前忙等，适用于锁持有极短的场景。"},
            {"volatile",
                    "Java 的 volatile 关键字保证可见性与禁止指令重排序，但不保证复合操作的原子性，典型用于双重检查单例的实例字段。",
                    "synchronized 通过监视器锁保证互斥与可见性，锁在 JVM 内部升级。",
                    "happens-before 规则定义了多线程间操作的内存可见性顺序。",
                    "内存屏障阻止特定指令重排序并强制刷新缓存，是可见性的底层机制。",
                    "原子类如 AtomicInteger 通过 CAS 实现无锁的原子更新。",
                    "双重检查单例用 volatile 修饰实例字段避免半初始化对象被发布。",
                    "伪共享发生在多核修改同一缓存行的不同变量，可用填充避免。",
                    "MESI 协议维护多核缓存一致性，保证写传播与失效。",
                    "写穿透指在写入时同时更新缓存与底层存储。",
                    "内存可见性指一个线程的写操作对其他线程立即可见。"},
            {"B+树",
                    "InnoDB 的索引采用 B+树，所有数据存储在叶子节点且叶子节点用双向链表串联，因此范围查询只需顺序遍历链表。",
                    "聚簇索引的叶子节点直接存放整行数据，表数据即按主键顺序组织。",
                    "二级索引的叶子节点存放主键值，回表通过主键再查聚簇索引。",
                    "覆盖索引指查询所需列都在索引中，无需回表减少 IO。",
                    "索引下推在存储引擎层利用索引过滤数据，减少回表次数。",
                    "自适应哈希索引为频繁访问的索引页建立哈希加速等值查询。",
                    "索引选择性越高越适合建索引，重复值多的列收益低。",
                    "InnoDB 的数据页默认 16KB，索引节点存放键值用于导航。",
                    "B+树在插入删除时通过页分裂与合并维持平衡。",
                    "最左前缀原则要求联合索引从最左列开始连续匹配。"},
            {"binlog",
                    "MySQL 的 binlog 是 Server 层记录逻辑变更的日志，用于主从复制与时间点的数据恢复，与存储引擎无关。",
                    "redo log 是 InnoDB 的物理日志，记录数据页的修改用于崩溃恢复。",
                    "undo log 是逻辑日志，记录行的反向操作用于事务回滚与 MVCC。",
                    "两阶段提交保证 redo 与 binlog 的一致性，避免主从数据分歧。",
                    "WAL 预写日志要求数据修改先写日志再改页，保证持久性。",
                    "崩溃恢复重放 redo 把未落盘的事务改动补回数据页。",
                    "组提交把多个事务的日志批量刷盘，提升写入吞吐。",
                    "redo 的刷盘策略由 innodb_flush_log_at_trx_commit 控制。",
                    "binlog 可设为行格式或语句格式，行格式更精确但体积大。",
                    "主从同步通过从库拉取主库 binlog 并重放实现数据一致。"},
            {"TIME_WAIT",
                    "TCP 的 TIME_WAIT 状态在主动关闭方最后发送 ACK 后等待 2MSL，目的是确保对端收到确认并让旧报文在网络中消散。",
                    "TCP 三次握手通过 SYN、SYN+ACK、ACK 同步双方初始序列号。",
                    "TCP 四次挥手通过 FIN 与 ACK 的交互释放双向连接。",
                    "SYN Flood 攻击利用半连接队列耗尽资源，可用 syn cookie 缓解。",
                    "拥塞控制通过慢启动与拥塞避免调节发送窗口。",
                    "滑动窗口机制在接收方处理能力内控制发送方的发送量。",
                    "流量控制防止发送方淹没接收方，由窗口大小动态反馈。",
                    "半连接指只完成 SYN 未完成握手的临时连接状态。",
                    "RST 复位报文用于异常时立即终止连接。",
                    "TCP 保活探测判断长时间空闲连接的对端是否仍然存活。"},
            {"三级缓存",
                    "Spring 用三级缓存解决单例 Bean 的循环依赖，通过提前暴露半成品对象引用让相互依赖的 Bean 完成注入。",
                    "Spring 的单例 Bean 在容器中只存在一个共享实例。",
                    "原型 Bean 每次获取都创建新实例，不进行缓存。",
                    "工厂方法模式由 FactoryBean 封装复杂对象的创建逻辑。",
                    "BeanPostProcessor 在初始化前后对 Bean 进行增强处理。",
                    "依赖注入是控制反转的具体实现，由容器装配对象依赖。",
                    "Bean 的生命周期包含实例化、填充属性与初始化销毁阶段。",
                    "作用域定义 Bean 的存在范围，如 singleton 与 prototype。",
                    "配置类用 @Configuration 标注并定义 Bean 的装配方式。",
                    "懒加载让 Bean 在首次被需要时而非容器启动时创建。"},
            {"RRF",
                    "混合检索中 RRF 倒数排名融合对每个召回源的排名使用 1/(k+rank) 加权求和，无需归一化分数即可合并向量与 BM25 结果。",
                    "BM25 是基于词频与逆文档频率的稀疏检索打分公式。",
                    "向量召回通过嵌入相似度找到语义相近的文档。",
                    "重排模型对初召结果用更精细的模型重新打分提升精度。",
                    "查询改写把用户原始问题扩展为多个等价表述以提升召回。",
                    "父子块策略用小块检索、大块送模型，兼顾精度与上下文。",
                    "标量量化把浮点向量压缩为低比特表示以节省内存。",
                    "HNSW 是基于多层图的近似最近邻检索算法，查询效率高。",
                    "余弦相似度衡量两个向量的方向一致性，忽略长度差异。",
                    "倒排索引记录词项到文档的映射，是全文检索的基础结构。"},
            {"布隆过滤器",
                    "缓存穿透可用布隆过滤器拦截不存在的 key，对查询先经位数组判断，若判定不存在则直接返回避免打到数据库。",
                    "缓存空值指把查不到的结果也写入缓存并设短过期，防止重复击穿。",
                    "互斥锁在缓存失效时只允许一个线程回源，其余等待结果。",
                    "逻辑过期不为缓存设真实 TTL，而是异步刷新避免集中失效。",
                    "随机过期时间为热点 key 的失效时间加上随机抖动防雪崩。",
                    "热点 key 限流对极端热点单独保护，防止单 key 拖垮集群。",
                    "本地缓存作为分布式缓存之前的更近一层减少远程调用。",
                    "延迟双删在写库前后各删一次缓存，降低读写并发导致的脏数据。",
                    "限流控制单位时间请求量，保护后端不被突发流量冲垮。",
                    "降级在依赖不可用时返回兜底数据，保证核心链路可用。"},
            {"CAS",
                    "CAS 比较并交换包含内存位置、预期值和新值三个操作数，只有预期值与当前值一致才更新，是无锁并发的核心。",
                    "自旋在 CAS 失败后循环重试，适用于锁持有极短的场景。",
                    "原子引用 AtomicReference 封装对象引用的原子更新。",
                    "版本号配合 CAS 可解决 ABA 问题，标识值的修改次数。",
                    "悲观锁假定会冲突，先加锁再操作，如数据库行锁。",
                    "乐观锁假定冲突少，提交时才校验版本，典型用 CAS 实现。",
                    "无锁队列借助 CAS 实现多线程安全入队出队而不加互斥锁。",
                    "内存屏障保证 CAS 前后的读写顺序符合预期。",
                    "原子变量如 AtomicInteger 提供线程安全的计数能力。",
                    "自旋开销在竞争激烈时会显著上升，应控制重试次数。"},
            {"令牌桶",
                    "令牌桶限流算法按固定速率向桶中发放令牌，请求只有取到令牌才被放行，允许一定程度的突发流量。",
                    "漏桶算法以恒定速率处理请求，超出容量的请求被丢弃或排队。",
                    "计数限流在固定窗口内统计请求数，超过阈值则拒绝。",
                    "滑动窗口限流把固定窗口细分为小格，平滑临界突变。",
                    "熔断在依赖错误率过高时快速失败，避免雪崩扩散。",
                    "降级在依赖不可用时返回兜底，保障核心流程。",
                    "Sentinel 通过规则控制流量、熔断与系统自适应保护。",
                    "热点参数限流对商品详情等极端热点单独设阈值。",
                    "并发限制约束同时处理的请求数，而非时间窗口总量。",
                    "排队等待让超额请求在队列中短暂等待而非立即拒绝。"}
    };

    @Test
    public void eval() throws Exception {
        String key = System.getenv("DASHSCOPE_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("需要环境变量 DASHSCOPE_API_KEY");
        }
        OkHttpClient client = new OkHttpClient();
        DashscopeEmbedder emb = new DashscopeEmbedder(client, BASE, key, MODEL);
        LuceneVectorStore store = new LuceneVectorStore(new ByteBuffersDirectory(), 1024);

        // —— 构建语料：每主题 10 篇（第 0 篇为目标），共 150 篇 ——
        List<String> docs = new ArrayList<>();
        List<Integer> gt = new ArrayList<>();   // 每条查询对应的目标 doc 全局下标
        List<String> queries = new ArrayList<>();
        for (int t = 0; t < TOPICS.length; t++) {
            String[] topic = TOPICS[t];
            int base = docs.size();
            for (int d = 0; d < topic.length - 1; d++) {  // topic[0]=签名词, 其后为目标+9干扰
                docs.add(topic[d + 1]);
            }
            // 每主题 2 条查询，都指向 base+0（目标文档）
            queries.add(topic[0] + " 是什么，解决什么问题");
            queries.add(specificQuery(t, topic[0]));
            gt.add(base + 0);
            gt.add(base + 0);
        }

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < docs.size(); i++) {
            List<String> pieces = TextChunker.chunk(docs.get(i), 500, 50);
            store.beginReplace(null, "eval", "doc-" + i);
            for (int j = 0; j < pieces.size(); j++) {
                float[] v = emb.embed(pieces.get(j)).vector();
                store.add(new KnowledgeChunk("doc-" + i + "#" + j, null, "eval", "doc-" + i, pieces.get(j), v));
            }
            store.commitBatch();
        }
        System.out.println("[ingest] " + docs.size() + " 篇文档入库耗时 " + (System.currentTimeMillis() - t0) + "ms");

        float[][] qv = new float[queries.size()][];
        List<Long> embTimes = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            long s = System.nanoTime();
            qv[i] = emb.embed(queries.get(i)).vector();
            embTimes.add((System.nanoTime() - s) / 1_000_000);
        }
        System.out.println("[embedding] 平均耗时 " + avg(embTimes) + "ms/次（P95 " + p95(embTimes) + "ms）\n");

        // 质量评估（topK=10 推导 recall@5 与 MRR@10）
        evalQuality("纯向量", i -> store.search(null, qv[i], 10), gt);
        evalQuality("纯 BM25", i -> store.searchKeyword(null, queries.get(i), 10), gt);
        evalQuality("混合 RRF", i -> store.searchHybrid(null, qv[i], queries.get(i), 10), gt);

        // 检索延迟（仅检索，不含 embedding；预热 1 轮后测 3 轮）
        measureLatency("纯向量", i -> store.search(null, qv[i], 10));
        measureLatency("纯 BM25", i -> store.searchKeyword(null, queries.get(i), 10));
        measureLatency("混合 RRF", i -> store.searchHybrid(null, qv[i], queries.get(i), 10));
    }

    private static String specificQuery(int t, String sig) {
        switch (t) {
            case 0:  return "MySQL 怎么防止幻读，间隙锁和 Next-Key Lock 有什么关系";
            case 1:  return "G1 为什么回收时不用全堆扫描，跨 Region 引用怎么记录";
            case 2:  return "InnoDB 怎么判断历史版本对当前事务可见";
            case 3:  return "Redis 有序集合怎么支持范围查询又能 O(1) 找成员";
            case 4:  return "为什么 Tomcat 要打破双亲委派模型";
            case 5:  return "AQS 用什么表示同步状态、用什么管理等待线程";
            case 6:  return "volatile 为什么能保证可见性但不能保证原子性";
            case 7:  return "为什么 InnoDB 用 B+树 做索引，范围查询怎么走";
            case 8:  return "MySQL 主从复制靠哪种日志实现";
            case 9:  return "TCP 主动关闭方为什么进入 TIME_WAIT 等 2MSL";
            case 10: return "Spring 怎么解决单例 Bean 的循环依赖";
            case 11: return "混合检索怎么把向量和 BM25 两套结果合到一起";
            case 12: return "缓存穿透怎么用布隆过滤器拦截不存在的 key";
            case 13: return "CAS 比较并交换包含哪三个操作数，ABA 怎么解决";
            case 14: return "令牌桶限流怎么放行请求，和漏桶有什么区别";
            default: return sig + " 的作用是什么";
        }
    }

    private void evalQuality(String name, IntFunction<List<KnowledgeChunk.ScoredChunk>> fn, List<Integer> gt) {
        int hit5 = 0;
        double mrrSum = 0;
        for (int i = 0; i < gt.size(); i++) {
            String prefix = "doc-" + gt.get(i) + "#";
            List<KnowledgeChunk.ScoredChunk> top = fn.apply(i);
            boolean in5 = false;
            int mrrRank = 0;
            for (int r = 0; r < top.size(); r++) {
                if (top.get(r).chunk().id().startsWith(prefix)) {
                    if (r < 5) in5 = true;
                    if (mrrRank == 0) mrrRank = r + 1;
                }
            }
            if (in5) hit5++;
            if (mrrRank > 0) mrrSum += 1.0 / mrrRank;
        }
        System.out.printf("[质量] %-8s recall@5=%.1f%%  MRR@10=%.3f%n",
                name, 100.0 * hit5 / gt.size(), mrrSum / gt.size());
    }

    private void measureLatency(String name, IntFunction<List<KnowledgeChunk.ScoredChunk>> fn) {
        for (int w = 0; w < 1; w++) {
            for (int i = 0; i < gtSize(); i++) fn.apply(i);
        }
        List<Long> all = new ArrayList<>();
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < gtSize(); i++) {
                long s = System.nanoTime();
                fn.apply(i);
                all.add((System.nanoTime() - s) / 1_000_000);
            }
        }
        System.out.printf("[延迟] %-8s 检索 P95=%.1fms（平均 %.2fms）%n", name, p95(all), avg(all));
    }

    private int gtSize() {
        return TOPICS.length * 2;
    }

    private static double avg(List<Long> xs) {
        return xs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private static double p95(List<Long> xs) {
        List<Long> s = new ArrayList<>(xs);
        s.sort(Comparator.naturalOrder());
        int idx = Math.min(s.size() - 1, (int) Math.ceil(0.95 * s.size()) - 1);
        return s.get(Math.max(0, idx));
    }
}
