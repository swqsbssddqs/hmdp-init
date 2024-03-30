package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.val;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private IVoucherService voucherService;
    @Autowired
    @Lazy
    private VoucherOrderServiceImpl voucherOrderService;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final BlockingQueue<VoucherOrder> seckillOrders=new ArrayBlockingQueue<>(1024*1024);;
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    @PostConstruct
    public void init(){
        SECKILL_ORDER_EXECUTOR.submit(()->{
            try {
                while(true){
                    VoucherOrder voucherOrder = seckillOrders.take();
                    voucherOrderService.handleVoucherOrder(voucherOrder);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    //保存预下单到redis，异步保存订单到数据库
    public Result createSeckillVoucherOrder(Long voucherId){
        Long userId = UserHolder.getUser().getId();

        Long res = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),voucherId.toString(),userId.toString());
        int r=res.intValue();
        if(r!=0){
            return Result.fail(r==1?"秒杀优惠券已被抢光！":"每个用户最多一单！");
        }
        //为0有购买资格
        Long orderId = redisIdWorker.nextId("order");
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setId(orderId);
        //存入阻塞队列
        seckillOrders.add(voucherOrder);
        return  Result.ok(orderId);
    }

    @Transactional
    public void handleVoucherOrder(VoucherOrder voucherOrder){
        boolean success =  seckillVoucherService.update()
                .setSql("stock =stock - 1 ")
                .eq("voucher_id",voucherOrder.getVoucherId()).gt("stock",0).update();

        save(voucherOrder);
        throw new RuntimeException("hello");
    }
}
