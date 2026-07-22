package com.damai.mylock;

import com.damai.mylock.annotation.ServiceLock;
import com.damai.mylock.aspect.ServiceLockAspect;
import com.damai.mylock.core.LockType;
import com.damai.mylock.core.ManageLocker;
import com.damai.mylock.factory.ServiceLockFactory;
import com.damai.mylock.util.ServiceLockTool;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServiceLockTest {

    private static RedissonClient redissonClient;
    private static ServiceLockTool lockTool;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6378")
                .setPassword("123456");
        redissonClient = Redisson.create(config);

        ManageLocker manageLocker = new ManageLocker(redissonClient);
        ServiceLockFactory factory = new ServiceLockFactory(manageLocker);
        lockTool = new ServiceLockTool(factory);

        System.out.println("===== Redis连接成功，测试环境准备完成 =====");
    }

    @AfterAll
    static void tearDown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
            System.out.println("===== Redis连接已关闭 =====");
        }
    }

    @Test
    @Order(1)
    @DisplayName("基本加锁解锁测试")
    void testBasicLock() {
        System.out.println("\n----- 测试1: 基本加锁解锁 -----");

        lockTool.execute(() -> {
            System.out.println("成功获取锁，执行业务逻辑");
        }, "basicLock", new String[]{"test001"});

        System.out.println("测试1通过");
    }

    @Test
    @Order(2)
    @DisplayName("锁竞争超时测试")
    void testLockCompetitionTimeout() throws InterruptedException {
        System.out.println("\n----- 测试2: 锁竞争（等待超时） -----");

        new Thread(() -> {
            lockTool.execute(LockType.Reentrant, () -> {
                System.out.println("线程1: 获取锁成功，开始执行5秒任务...");
                try { Thread.sleep(5000); } catch (InterruptedException e) { }
                System.out.println("线程1: 任务完成，释放锁");
            }, "competitionLock", new String[]{"test002"}, 20);
        }).start();

        Thread.sleep(300);

        Assertions.assertThrows(RuntimeException.class, () -> {
            lockTool.execute(LockType.Reentrant, () -> {
                System.out.println("线程2: 获取锁成功（不应该执行到这里）");
            }, "competitionLock", new String[]{"test002"}, 1);
        }, "预期线程2获取锁失败");

        System.out.println("测试2通过");
    }

    @Test
    @Order(3)
    @DisplayName("不同key的锁互不影响")
    void testDifferentKeys() throws InterruptedException {
        System.out.println("\n----- 测试3: 不同key的锁互不影响 -----");

        CountDownLatch latch = new CountDownLatch(2);

        new Thread(() -> {
            lockTool.execute(() -> {
                System.out.println("线程1: 获取锁key1成功，执行3秒任务...");
                try { Thread.sleep(3000); } catch (InterruptedException e) { }
                System.out.println("线程1: 任务完成");
                latch.countDown();
            }, "multiLock", new String[]{"key1"});
        }).start();

        new Thread(() -> {
            lockTool.execute(() -> {
                System.out.println("线程2: 获取锁key2成功，立即执行任务");
                latch.countDown();
            }, "multiLock", new String[]{"key2"});
        }).start();

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        Assertions.assertTrue(completed, "所有线程应该完成");

        System.out.println("测试3通过");
    }

    @Test
    @Order(4)
    @DisplayName("可重入锁测试")
    void testReentrantLock() {
        System.out.println("\n----- 测试4: 可重入锁 -----");

        lockTool.execute(() -> {
            System.out.println("外层: 获取锁成功");

            lockTool.execute(() -> {
                System.out.println("内层: 获取锁成功（可重入）");
            }, "reentrantLock", new String[]{"test004"});

            System.out.println("外层: 任务完成");
        }, "reentrantLock", new String[]{"test004"});

        System.out.println("测试4通过");
    }

    @Test
    @Order(5)
    @DisplayName("高并发锁测试")
    void testHighConcurrency() throws InterruptedException {
        System.out.println("\n----- 测试5: 高并发锁测试 -----");

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();

                    lockTool.execute(() -> {
                        int value = counter.incrementAndGet();
                        System.out.println("线程" + Thread.currentThread().getId() + ": 计数器值=" + value);
                        try { Thread.sleep(100); } catch (InterruptedException e) { }
                    }, "concurrencyLock", new String[]{"test005"});

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        System.out.println("释放所有线程开始竞争...");
        startLatch.countDown();

        boolean completed = endLatch.await(30, TimeUnit.SECONDS);
        Assertions.assertTrue(completed, "所有线程应该完成");

        Assertions.assertEquals(threadCount, counter.get(),
                "计数器应该等于线程数，说明锁正确保护了临界区");

        System.out.println("测试5通过，最终计数器值=" + counter.get());
    }

    @Test
    @Order(6)
    @DisplayName("异常情况下自动解锁")
    void testExceptionUnlock() throws InterruptedException {
        System.out.println("\n----- 测试6: 异常情况下自动解锁 -----");

        try {
            lockTool.execute(() -> {
                System.out.println("线程1: 获取锁成功");
                throw new RuntimeException("模拟业务异常");
            }, "exceptionLock", new String[]{"test006"});
        } catch (RuntimeException e) {
            System.out.println("捕获预期异常: " + e.getMessage());
        }

        Thread.sleep(500);

        boolean[] lockAcquired = {false};
        lockTool.execute(() -> {
            lockAcquired[0] = true;
            System.out.println("线程2: 获取锁成功（证明异常时锁已释放）");
        }, "exceptionLock", new String[]{"test006"});

        Assertions.assertTrue(lockAcquired[0], "第二个线程应该能获取锁");

        System.out.println("测试6通过");
    }

    static class AnnotationTestService {
        private final AtomicInteger counter = new AtomicInteger(0);

        @ServiceLock(name = "annotationTest", keys = {"#id"}, waitTime = 10)
        public String processWithAnnotation(String id) {
            System.out.println("Annotation method processWithAnnotation(" + id + ") acquired lock");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int count = counter.incrementAndGet();
            return "Annotation processed, counter: " + count;
        }

        @ServiceLock(name = "annotationReentrant", keys = {"#id"})
        public String outerAnnotationMethod(String id) {
            System.out.println("Outer annotation method acquired lock");
            String result = innerAnnotationMethod(id);
            return "Outer result: " + result;
        }

        @ServiceLock(name = "annotationReentrant", keys = {"#id"})
        public String innerAnnotationMethod(String id) {
            System.out.println("Inner annotation method acquired lock (reentrant)");
            return "Inner processed";
        }

        public int getCounter() {
            return counter.get();
        }

        public void resetCounter() {
            counter.set(0);
        }
    }

    @Test
    @Order(7)
    @DisplayName("注解方式测试 - ServiceLock注解配置验证")
    void testAnnotationConfiguration() throws NoSuchMethodException {
        System.out.println("\n----- 测试7: 注解方式测试 -----");

        ServiceLock annotation = AnnotationTestService.class.getMethod("processWithAnnotation", String.class)
                .getAnnotation(ServiceLock.class);

        Assertions.assertNotNull(annotation);
        Assertions.assertEquals("annotationTest", annotation.name());
        Assertions.assertArrayEquals(new String[]{"#id"}, annotation.keys());
        Assertions.assertEquals(10, annotation.waitTime());
        Assertions.assertEquals(LockType.Reentrant, annotation.lockType());

        System.out.println("注解配置验证通过: name=" + annotation.name() + ", keys=" + String.join(",", annotation.keys()));
        System.out.println("测试7通过");
    }
}