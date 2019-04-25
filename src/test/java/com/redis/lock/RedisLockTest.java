package com.redis.lock;

import com.redis.lock.utils.RedisLockHelper;
import org.junit.Before;
import org.springframework.util.StopWatch;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @description:
 * @author: 13572
 * @create: 2019/04/24 22:54
 */
public class RedisLockTest {
    private ExecutorService executorService = Executors.newFixedThreadPool(200);
    private JedisPool jedisPool;
    private RedisLockTest() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(60);
        config.setMaxIdle(60);
        config.setTestOnReturn(true);
        config.setTestOnBorrow(true);
        String password = "123456";
        jedisPool = new JedisPool(config, "39.106.63.214", 6379, 10000, password);
    }

    public static void main(String[] args) throws InterruptedException {
        RedisLockTest redisLockTest = new RedisLockTest();
        RedisLockHelper redisLockHelper = new RedisLockHelper();
        for (int i = 0; i < 200; i++) {
            redisLockTest.executorService.execute(()->{
                try (Jedis jedis = redisLockTest.jedisPool.getResource()){
                    redisLockHelper.lock(jedis, "pjmike", "1", 20, TimeUnit.SECONDS);
                    System.out.println("加锁成功");
                    redisLockHelper.unlock(jedis, "pjmike", "1");
                }
            });
        }
        redisLockTest.executorService.awaitTermination(1, TimeUnit.SECONDS);
        redisLockTest.executorService.shutdown();
    }

}
