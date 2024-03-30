package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    public Result queryById(Long id) {
        //Shop shop = queryPassThrough(id);
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //Shop shop = queryMutexLock(id);
        //Shop shop = queryLogicalExpire(id);

        if(shop==null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }
    public Shop queryMutexLock(Long id){
        String key = CACHE_SHOP_KEY+id;
        String jsonShop = stringRedisTemplate.opsForValue().get(key);
        //缓存命中
        if(StrUtil.isNotBlank(jsonShop)){
            return JSONUtil.toBean(jsonShop,Shop.class);
        }
        if(jsonShop!=null){
            return null;
        }
        Shop shop;
        //查数据库
        String lockKey = LOCK_SHOP_KEY;
        try {
            boolean isLock = tryLock(lockKey);
            //是否获取成功
            if (!isLock) {
                //获取失败 休眠并且重试
                Thread.sleep(50);
                return queryMutexLock(id);
            }
            //成功 通过id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            if (shop == null) {
                //redis写入空值
                stringRedisTemplate.opsForValue().set(lockKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //数据库不存在 返回错误
                return null;
            }
            //数据库存在 写入redis
            stringRedisTemplate.opsForValue().set(lockKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY+id;
        String jsonShop = stringRedisTemplate.opsForValue().get(key);
        //缓存未命中
        if(StrUtil.isBlank(jsonShop)){
            return null;
        }

        RedisData<Shop> redisData= JSONUtil.toBean(jsonShop, new TypeReference<RedisData<Shop>>() {},true);

        //查数据库
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            return redisData.getData();
        }

        String lockKey = LOCK_SHOP_KEY;
        boolean isLock = tryLock(lockKey);

        if(isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id, 20);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        return redisData.getData();
    }
    public Shop queryPassThrough(Long id){
        String key = CACHE_SHOP_KEY+id;
        String jsonShop = stringRedisTemplate.opsForValue().get(key);
        //缓存命中
        if(StrUtil.isNotBlank(jsonShop)){

            return JSONUtil.toBean(jsonShop,Shop.class);
        }
        if(jsonShop!=null){
            return null;
        }
        //查数据库
        Shop shop = getById(id);

        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    public void saveShopToRedis(Long id,int expireSeconds){
        Shop shop = getById(id);
        RedisData<Shop>  redisData= new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }

    public boolean tryLock(String key){
        Boolean flag= stringRedisTemplate.opsForValue().setIfAbsent(key,"1",LOCK_SHOP_TTL,TimeUnit.MILLISECONDS);
        return BooleanUtil.isTrue(flag);
    }
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }
    @Transactional
    public Result update(Shop shop) {

        Long id = shop.getId();
        if(id==null){
            return Result.fail("id不能为空");
        }

        updateById(shop);

        String key = CACHE_SHOP_KEY+id;
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
