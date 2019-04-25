package com.redis.lock.controller;

import com.redis.lock.annotation.RedisLock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @description:
 * @author: pjmike
 * @create: 2019/04/24 21:30
 */
@RestController
public class TestController {
    @RedisLock(key = "redis_lock")
    @GetMapping("/index")
    public String index() {
        return "index";
    }
}
