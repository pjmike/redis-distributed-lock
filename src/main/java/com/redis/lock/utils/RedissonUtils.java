package com.redis.lock.utils;

import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.TimeUnit;

/**
 * @description:
 * @author: pjmike
 * @create: 2019/04/24 00:39
 */
public class RedissonUtils {
    private static void trylock() {
        // 1. 配置文件
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("123456")
                .setDatabase(0);
        //2. 构造RedissonClient
        RedissonClient redissonClient = Redisson.create(config);

        //3. 设置锁定资源名称
        RLock lock = redissonClient.getLock("redlock");

        boolean isLock;
        try {
            isLock = lock.tryLock(500, 30000, TimeUnit.MILLISECONDS);
            if (isLock) {
                //TODO 成功获取到锁 执行业务逻辑
                System.out.println("获取到锁，执行相应的业务逻辑");
                Thread.sleep(15000);
            }
        } catch (InterruptedException e) {
            //TODO
        } finally {
            //解锁操作
            lock.unlock();
        }
    }

    public static void main(String[] args) {
        trylock();
        System.exit(0);
    }

    public static void lock() {
        // 1. 配置文件
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("123456")
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

    }
}
