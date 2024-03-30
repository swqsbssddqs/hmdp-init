package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class CacheClient {
    @Autowired
    private  StringRedisTemplate stringRedisTemplate;
    public  void set(String key,Object object,Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(object),time,unit);
    }
    public  void setLogicalExpire(String key,Object object,Long time, TimeUnit unit){
        RedisData redisData = new RedisData<>();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        redisData.setData(object);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),time,unit);
    }
    public <R,ID>  R  queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> getId,Long time ,TimeUnit unit){
        String key = keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //缓存命中
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if(json!=null){
            return null;
        }
        //查数据库
        R r= getId.apply(id);

        if(r==null){
            this.set(key,"",CACHE_NULL_TTL,unit);
            return null;
        }
        this.set(key,r,time,unit);
        return r;
    }
    public <R, ID> R queryWithLogicalExpire(String keyPrefix
            , ID id
            , Class<R> type
            , Function<ID, R> dbFallback
            , Long time
            , TimeUnit unit) {
        String key = keyPrefix + id;
        //从redis中查询
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StringUtils.isEmpty(json)) {
            //不存在返回空
            return null;
        }
        //命中 反序列化
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject jsonObject = (JSONObject) redisData.getData();
        R r = BeanUtil.toBean(jsonObject, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期 直接返回
            return r;
        }
        //已过期
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        //是否获取锁成功
        if (flag) {
            //成功 异步重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R newR = dbFallback.apply(id);
                    //写入redis
                    this.setLogicalExpire(key,newR,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //返回过期商铺信息
        return r;
    }

    /**
     * 简易线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 获取锁
     *
     * @param key 关键
     * @return boolean
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key 关键
     */
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
