## 前言
本篇文章主要介绍基于Redis的分布式锁实现到底是怎么一回事，其中参考了许多大佬写的文章，算是对分布式锁做一个总结

## 分布式锁概览

在多线程的环境下，为了保证一个代码块在同一时间只能由一个线程访问，Java中我们一般可以使用synchronized语法和ReetrantLock去保证，这实际上是本地锁的方式。但是现在公司都是流行分布式架构，在分布式环境下，如何保证不同节点的线程同步执行呢？

实际上，对于分布式场景，我们可以使用分布式锁，它是控制分布式系统之间**互斥访问共享资源**的一种方式。

比如说在一个分布式系统中，多台机器上部署了多个服务，当客户端一个用户发起一个数据插入请求时，如果没有分布式锁机制保证，那么那多台机器上的多个服务可能进行并发插入操作，导致数据重复插入，对于某些不允许有多余数据的业务来说，这就会造成问题。而分布式锁机制就是为了解决类似这类问题，保证多个服务之间互斥的访问共享资源，如果一个服务抢占了分布式锁，其他服务没获取到锁，就不进行后续操作。大致意思如下图所示（不一定准确）：

![分布式锁](https://pjmike-1253796536.cos.ap-beijing.myqcloud.com/%E5%88%86%E5%B8%83%E5%BC%8F%E9%94%81.png)



### 分布式锁的特点

分布式锁一般有如下的特点：

- 互斥性： 同一时刻只能有一个线程持有锁
- 可重入性： 同一节点上的同一个线程如果获取了锁之后能够再次获取锁
- 锁超时：和J.U.C中的锁一样支持锁超时，防止死锁
- 高性能和高可用： 加锁和解锁需要高效，同时也需要保证高可用，防止分布式锁失效
- 具备阻塞和非阻塞性：能够及时从阻塞状态中被唤醒

### 分布式锁的实现方式
我们一般实现分布式锁有以下几种方式：
- 基于数据库
- 基于Redis
- 基于zookeeper

本篇文章主要介绍基于Redis如何实现分布式锁

## Redis的分布式锁实现

### 1. 利用setnx+expire命令 (错误的做法)

Redis的SETNX命令，setnx key value，将key设置为value，当键不存在时，才能成功，若键存在，什么也不做，成功返回1，失败返回0 。 SETNX实际上就是SET IF NOT Exists的缩写

因为分布式锁还需要超时机制，所以我们利用expire命令来设置，所以利用setnx+expire命令的核心代码如下：

```java
public boolean tryLock(String key,String requset,int timeout) {
    Long result = jedis.setnx(key, requset);
    // result = 1时，设置成功，否则设置失败
    if (result == 1L) {
        return jedis.expire(key, timeout) == 1L;
    } else {
        return false;
    }
}
```
实际上上面的步骤是有问题的，setnx和expire是分开的两步操作，不具有原子性，如果执行完第一条指令应用异常或者重启了，锁将无法过期。

一种改善方案就是使用Lua脚本来保证原子性（包含setnx和expire两条指令）

### 2. 使用Lua脚本（包含setnx和expire两条指令）
代码如下
```java
public boolean tryLock_with_lua(String key, String UniqueId, int seconds) {
    String lua_scripts = "if redis.call('setnx',KEYS[1],ARGV[1]) == 1 then" +
            "redis.call('expire',KEYS[1],ARGV[2]) return 1 else return 0 end";
    List<String> keys = new ArrayList<>();
    List<String> values = new ArrayList<>();
    keys.add(key);
    values.add(UniqueId);
    values.add(String.valueOf(seconds));
    Object result = jedis.eval(lua_scripts, keys, values);
    //判断是否成功
    return result.equals(1L);
}
```
### 3.  使用 set key value [EX seconds][PX milliseconds][NX|XX] 命令 (正确做法)
Redis在 2.6.12 版本开始，为 SET 命令增加一系列选项：

```
SET key value[EX seconds][PX milliseconds][NX|XX]
```
- EX seconds: 设定过期时间，单位为秒
- PX milliseconds: 设定过期时间，单位为毫秒
- NX: 仅当key不存在时设置值
- XX: 仅当key存在时设置值

set命令的nx选项，就等同于setnx命令，代码过程如下：
```java
public boolean tryLock_with_set(String key, String UniqueId, int seconds) {
    return "OK".equals(jedis.set(key, UniqueId, "NX", "EX", seconds));
}
```
value必须要具有唯一性，比如UUID，至于为什么要保证唯一性？我的理解是当我们释放锁时需要验证value值，如果value不唯一，有多个相同的值，释放锁的过程就可能会出现问题。

#### 关于释放锁的问题
释放锁时需要验证value值，也就是说我们在获取锁的时候需要设置一个value，不能直接用del key这种粗暴的方式，因为直接del key任何客户端都可以进行解锁了，所以**解锁时，我们需要判断锁是否是自己的，基于value值来判断**，代码如下：

```
public boolean releaseLock_with_lua(String key,String value) {
    String luaScript = "if redis.call('get',KEYS[1]) == ARGV[1] then " +
            "return redis.call('del',KEYS[1]) else return 0 end";
    return jedis.eval(luaScript, Collections.singletonList(key), Collections.singletonList(value)).equals(1L);
}
```
这里使用Lua脚本的方式，尽量保证原子性。


使用 `set key value [EX seconds][PX milliseconds][NX|XX]` 命令 看上去很OK，实际上在Redis集群的时候也会出现问题，比如说A客户端在Redis的master节点上拿到了锁，但是这个加锁的key还没有同步到slave节点，master故障，发生故障转移，一个slave节点升级为master节点，B客户端也可以获取同个key的锁，但客户端A也已经拿到锁了，这就导致多个客户端都拿到锁。

所以针对Redis集群这种情况，还有其他方案

### 4. Redlock算法 与 Redisson 实现

Redis作者 antirez基于分布式环境下提出了一种更高级的分布式锁的实现Redlock，原理如下：


> 下面参考文章[Redlock：Redis分布式锁最牛逼的实现](https://mp.weixin.qq.com/s?__biz=MzU5ODUwNzY1Nw==&mid=2247484155&idx=1&sn=0c73f45f2f641ba0bf4399f57170ac9b&scene=21#wechat_redirect) 和 https://redis.io/topics/distlock


假设有5个独立的Redis节点（**注意这里的节点可以是5个Redis单master实例，也可以是5个Redis Cluster集群，但并不是有5个主节点的cluster集群**）：

- 获取当前Unix时间，以毫秒为单位
- 依次尝试从5个实例，使用相同的key和具有唯一性的value(例如UUID)获取锁，当向Redis请求获取锁时，客户端应该设置一个网络连接和响应超时时间，这个超时时间应用小于锁的失效时间，例如你的锁自动失效时间为10s，则超时时间应该在5~50毫秒之间，这样可以避免服务器端Redis已经挂掉的情况下，客户端还在死死地等待响应结果。如果服务端没有在规定时间内响应，客户端应该尽快尝试去另外一个Redis实例请求获取锁
- 客户端使用当前时间减去开始获取锁时间（步骤1记录的时间）就得到获取锁使用的时间，当且仅当从大多数(N/2+1，这里是3个节点)的Redis节点都取到锁，并且使用的时间小于锁失败时间时，锁才算获取成功。
- 如果取到了锁，key的真正有效时间等于有效时间减去获取锁所使用的时间（步骤3计算的结果）
- 如果某些原因，获取锁失败（没有在至少N/2+1个Redis实例取到锁或者取锁时间已经超过了有效时间），客户端应该在所有的Redis实例上进行解锁（即便某些Redis实例根本就没有加锁成功，防止某些节点获取到锁但是客户端没有得到响应而导致接下来的一段时间不能被重新获取锁）

#### Redisson实现简单分布式锁

对于Java用户而言，我们经常使用Jedis，Jedis是Redis的Java客户端，除了Jedis之外，Redisson也是Java的客户端，Jedis是阻塞式I/O，而Redisson底层使用Netty可以实现非阻塞I/O，该客户端封装了锁的，继承了J.U.C的Lock接口，所以我们可以像使用ReentrantLock一样使用Redisson，具体使用过程如下。

1. 首先加入POM依赖

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.10.6</version>
</dependency>
```
2. 使用Redisson，代码如下(与使用ReentrantLock类似）


```java
// 1. 配置文件
Config config = new Config();
config.useSingleServer()
        .setAddress("redis://127.0.0.1:6379")
        .setPassword(RedisConfig.PASSWORD)
        .setDatabase(0);
//2. 构造RedissonClient
RedissonClient redissonClient = Redisson.create(config);

//3. 设置锁定资源名称
RLock lock = redissonClient.getLock("redlock");
lock.lock();
try {
    System.out.println("获取锁成功，实现业务逻辑");
    Thread.sleep(10000);
} catch (InterruptedException e) {
    e.printStackTrace();
} finally {
    lock.unlock();
}
```
关于Redlock算法的实现，在Redisson中我们可以使用RedissonRedLock来完成，具体使用细节可以参考大佬的文章： https://mp.weixin.qq.com/s/8uhYult2h_YUHT7q7YCKYQ

## Redis实现的分布式锁轮子

下面利用SpringBoot + Jedis + AOP的组合来实现一个简易的分布式锁。



### 1. 自定义注解
自定义一个注解，被注解的方法会执行获取分布式锁的逻辑
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface RedisLock {
    /**
     * 业务键
     *
     * @return
     */
    String key();
    /**
     * 锁的过期秒数,默认是5秒
     *
     * @return
     */
    int expire() default 5;

    /**
     * 尝试加锁，最多等待时间
     *
     * @return
     */
    long waitTime() default Long.MIN_VALUE;
    /**
     * 锁的超时时间单位
     *
     * @return
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
```
### 2. AOP拦截器实现
在AOP中我们去执行获取分布式锁和释放分布式锁的逻辑，代码如下：
```java
@Aspect
@Component
public class LockMethodAspect {
    @Autowired
    private RedisLockHelper redisLockHelper;
    @Autowired
    private JedisUtil jedisUtil;
    private Logger logger = LoggerFactory.getLogger(LockMethodAspect.class);

    @Around("@annotation(com.redis.lock.annotation.RedisLock)")
    public Object around(ProceedingJoinPoint joinPoint) {
        Jedis jedis = jedisUtil.getJedis();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        RedisLock redisLock = method.getAnnotation(RedisLock.class);
        String value = UUID.randomUUID().toString();
        String key = redisLock.key();
        try {
            final boolean islock = redisLockHelper.lock(jedis,key, value, redisLock.expire(), redisLock.timeUnit());
            logger.info("isLock : {}",islock);
            if (!islock) {
                logger.error("获取锁失败");
                throw new RuntimeException("获取锁失败");
            }
            try {
                return joinPoint.proceed();
            } catch (Throwable throwable) {
                throw new RuntimeException("系统异常");
            }
        }  finally {
            logger.info("释放锁");
            redisLockHelper.unlock(jedis,key, value);
            jedis.close();
        }
    }
}

```
### 3. Redis实现分布式锁核心类
```java
@Component
public class RedisLockHelper {
    private long sleepTime = 100;
    /**
     * 直接使用setnx + expire方式获取分布式锁
     * 非原子性
     *
     * @param key
     * @param value
     * @param timeout
     * @return
     */
    public boolean lock_setnx(Jedis jedis,String key, String value, int timeout) {
        Long result = jedis.setnx(key, value);
        // result = 1时，设置成功，否则设置失败
        if (result == 1L) {
            return jedis.expire(key, timeout) == 1L;
        } else {
            return false;
        }
    }

    /**
     * 使用Lua脚本，脚本中使用setnex+expire命令进行加锁操作
     *
     * @param jedis
     * @param key
     * @param UniqueId
     * @param seconds
     * @return
     */
    public boolean Lock_with_lua(Jedis jedis,String key, String UniqueId, int seconds) {
        String lua_scripts = "if redis.call('setnx',KEYS[1],ARGV[1]) == 1 then" +
                "redis.call('expire',KEYS[1],ARGV[2]) return 1 else return 0 end";
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        keys.add(key);
        values.add(UniqueId);
        values.add(String.valueOf(seconds));
        Object result = jedis.eval(lua_scripts, keys, values);
        //判断是否成功
        return result.equals(1L);
    }

    /**
     * 在Redis的2.6.12及以后中,使用 set key value [NX] [EX] 命令
     *
     * @param key
     * @param value
     * @param timeout
     * @return
     */
    public boolean lock(Jedis jedis,String key, String value, int timeout, TimeUnit timeUnit) {
        long seconds = timeUnit.toSeconds(timeout);
        return "OK".equals(jedis.set(key, value, "NX", "EX", seconds));
    }

    /**
     * 自定义获取锁的超时时间
     *
     * @param jedis
     * @param key
     * @param value
     * @param timeout
     * @param waitTime
     * @param timeUnit
     * @return
     * @throws InterruptedException
     */
    public boolean lock_with_waitTime(Jedis jedis,String key, String value, int timeout, long waitTime,TimeUnit timeUnit) throws InterruptedException {
        long seconds = timeUnit.toSeconds(timeout);
        while (waitTime >= 0) {
            String result = jedis.set(key, value, "nx", "ex", seconds);
            if ("OK".equals(result)) {
                return true;
            }
            waitTime -= sleepTime;
            Thread.sleep(sleepTime);
        }
        return false;
    }
    /**
     * 错误的解锁方法—直接删除key
     *
     * @param key
     */
    public void unlock_with_del(Jedis jedis,String key) {
        jedis.del(key);
    }

    /**
     * 使用Lua脚本进行解锁操纵，解锁的时候验证value值
     *
     * @param jedis
     * @param key
     * @param value
     * @return
     */
    public boolean unlock(Jedis jedis,String key,String value) {
        String luaScript = "if redis.call('get',KEYS[1]) == ARGV[1] then " +
                "return redis.call('del',KEYS[1]) else return 0 end";
        return jedis.eval(luaScript, Collections.singletonList(key), Collections.singletonList(value)).equals(1L);
    }
}
```

### 4. Controller层控制
定义一个TestController来测试我们实现的分布式锁
```java
@RestController
public class TestController {
    @RedisLock(key = "redis_lock")
    @GetMapping("/index")
    public String index() {
        return "index";
    }
}
```

## 小结
分布式锁重点在于互斥性，在任意一个时刻，只有一个客户端获取了锁。在实际的生产环境中，分布式锁的实现可能会更复杂，而我这里的讲述主要针对的是单机环境下的基于Redis的分布式锁实现，至于Redis集群环境并没有过多涉及，有兴趣的朋友可以查阅相关资料。

## 参考资料 & 鸣谢

- https://mp.weixin.qq.com/s/eHsuEc8Dq3h1Kz1uqBWsjA
- https://mp.weixin.qq.com/s/y2HPj2ji2KLS_eTR5nBnDA
- https://mp.weixin.qq.com/s/8uhYult2h_YUHT7q7YCKYQ
- https://mp.weixin.qq.com/s/xCe2ljuhMWD2rsstmNab_Q
- https://crossoverjie.top/2018/03/29/distributed-lock/distributed-lock-redis/
- https://blog.battcn.com/2018/06/13/springboot/v2-cache-redislock/#%E5%85%B7%E4%BD%93%E4%BB%A3%E7%A0%81
- https://redis.io/topics/distlock
