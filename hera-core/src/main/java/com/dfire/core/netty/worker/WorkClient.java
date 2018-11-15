package com.dfire.core.netty.worker;


import com.dfire.common.entity.vo.HeraDebugHistoryVo;
import com.dfire.common.entity.vo.HeraJobHistoryVo;
import com.dfire.common.enums.StatusEnum;
import com.dfire.common.util.BeanConvertUtils;
import com.dfire.common.util.ActionUtil;
import com.dfire.common.util.NamedThreadFactory;
import com.dfire.core.config.HeraGlobalEnvironment;
import com.dfire.core.job.Job;
import com.dfire.core.message.HeartBeatInfo;
import com.dfire.core.netty.worker.request.WorkerHandleWebRequest;
import com.dfire.core.netty.worker.request.WorkerHandlerHeartBeat;
import com.dfire.logs.HeartLog;
import com.dfire.logs.HeraLog;
import com.dfire.logs.SocketLog;
import com.dfire.protocol.JobExecuteKind.ExecuteKind;
import com.dfire.protocol.ResponseStatus;
import com.dfire.protocol.RpcHeartBeatMessage.AllHeartBeatInfoMessage;
import com.dfire.protocol.RpcHeartBeatMessage.HeartBeatMessage;
import com.dfire.protocol.RpcSocketMessage;
import com.dfire.protocol.RpcWebResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.Data;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 10:34 2018/1/10
 * @desc
 */
@Data
@Component
public class WorkClient {

    private Bootstrap bootstrap;
    private EventLoopGroup eventLoopGroup;
    private WorkContext workContext = new WorkContext();
    private ScheduledExecutorService service;
    private AtomicBoolean clientSwitch = new AtomicBoolean(false);
    public ScheduledThreadPoolExecutor workSchedule;
    {
        workSchedule = new ScheduledThreadPoolExecutor(3, new NamedThreadFactory("work-schedule", false));
        workSchedule.setKeepAliveTime(5, TimeUnit.MINUTES);
        workSchedule.allowCoreThreadTimeOut(true);
    }
    /**
     * ProtobufVarint32FrameDecoder:  针对protobuf协议的ProtobufVarint32LengthFieldPrepender()所加的长度属性的解码器
     * <pre>
     *  * BEFORE DECODE (302 bytes)       AFTER DECODE (300 bytes)
     *  * +--------+---------------+      +---------------+
     *  * | Length | Protobuf Data |----->| Protobuf Data |
     *  * | 0xAC02 |  (300 bytes)  |      |  (300 bytes)  |
     *  * +--------+---------------+      +---------------+
     * </pre>
     * <p>
     * ProtobufVarint32LengthFieldPrepender: 对protobuf协议的的消息头上加上一个长度为32的整形字段,用于标志这个消息的长度。
     * <pre>
     * * BEFORE DECODE (300 bytes)       AFTER DECODE (302 bytes)
     *  * +---------------+               +--------+---------------+
     *  * | Protobuf Data |-------------->| Length | Protobuf Data |
     *  * |  (300 bytes)  |               | 0xAC02 |  (300 bytes)  |
     *  * +---------------+               +--------+---------------+
     * </pre>
     */
    public void init(ApplicationContext applicationContext) {
        if (!clientSwitch.compareAndSet(false, true)) {
            return;
        }

        workContext.setWorkClient(this);
        workContext.setApplicationContext(applicationContext);
        eventLoopGroup = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new IdleStateHandler(0, 0, 5, TimeUnit.SECONDS))
                                .addLast("frameDecoder", new ProtobufVarint32FrameDecoder())
                                .addLast("decoder", new ProtobufDecoder(RpcSocketMessage.SocketMessage.getDefaultInstance()))
                                .addLast("frameEncoder", new ProtobufVarint32LengthFieldPrepender())
                                .addLast("encoder", new ProtobufEncoder())
                                .addLast(new WorkHandler(workContext));
                    }
                });
        HeraLog.info("init work client success ");

        workSchedule.schedule(new Runnable() {

            private WorkerHandlerHeartBeat workerHandlerHeartBeat = new WorkerHandlerHeartBeat();
            private int failCount = 0;

            @Override
            public void run() {
                try {
                    if (workContext.getServerChannel() != null) {
                        ChannelFuture channelFuture = workerHandlerHeartBeat.send(workContext);
                        channelFuture.addListener((future) -> {
                            if (!future.isSuccess()) {
                                failCount++;
                                SocketLog.error("send heart beat failed ,failCount :" + failCount);
                            } else {
                                failCount = 0;
                                HeartLog.info("send heart beat success:{}", workContext.getServerChannel().remoteAddress());
                            }
                            if (failCount > 10) {
                                future.cancel(true);
                                SocketLog.warn("cancel connect server ,failCount:" + failCount);
                            }
                        });
                    } else {
                        SocketLog.error("server channel can not find on " + WorkContext.host);
                    }
                } catch (Exception e) {
                    SocketLog.error("heart beat error:", e);
                } finally {
                    workSchedule.schedule(this, (failCount + 1) * HeraGlobalEnvironment.getHeartBeat(), TimeUnit.SECONDS);
                }
            }
        }, HeraGlobalEnvironment.getHeartBeat(), TimeUnit.SECONDS);

        workSchedule.scheduleWithFixedDelay(new Runnable() {
            private void editLog(Job job, Exception e) {
                try {
                    HeraJobHistoryVo his = job.getJobContext().getHeraJobHistory();
                    String logContent = his.getLog().getContent();
                    if (logContent == null) {
                        logContent = "";
                    }
                    HeraLog.error("log output error!\n" +
                            "[actionId:" + his.getJobId() +
                            ", hisId:" + his.getId() +
                            ", logLength:" +
                            logContent.length() + "]", e);
                } catch (Exception ex) {
                    HeraLog.error("log exception error!");
                }
            }


            private void editDebugLog(Job job, Exception e) {
                try {
                    HeraDebugHistoryVo history = job.getJobContext().getDebugHistory();
                    String logContent = history.getLog().getContent();
                    if (logContent == null) {
                        logContent = "";
                    }
                    HeraLog.error("log output error!\n" +
                            "[fileId:" + history.getFileId() +
                            ", hisId:" + history.getId() +
                            ", logLength:" +
                            logContent.length() + "]", e);
                } catch (Exception ex) {
                    HeraLog.error("log exception error!");
                }
            }

            @Override
            public void run() {

                try {
                    for (Job job : new HashSet<>(workContext.getRunning().values())) {
                        try {
                            HeraJobHistoryVo history = job.getJobContext().getHeraJobHistory();
                            workContext.getJobHistoryService().updateHeraJobHistoryLog(BeanConvertUtils.convert(history));
                        } catch (Exception e) {
                            editDebugLog(job, e);
                        }
                    }

                    for (Job job : new HashSet<>(workContext.getManualRunning().values())) {
                        try {
                            HeraJobHistoryVo history = job.getJobContext().getHeraJobHistory();
                            workContext.getJobHistoryService().updateHeraJobHistoryLog(BeanConvertUtils.convert(history));
                        } catch (Exception e) {
                            editLog(job, e);
                        }
                    }

                    for (Job job : new HashSet<>(workContext.getDebugRunning().values())) {
                        try {
                            HeraDebugHistoryVo history = job.getJobContext().getDebugHistory();
                            workContext.getDebugHistoryService().updateLog(BeanConvertUtils.convert(history));
                        } catch (Exception e) {
                            editDebugLog(job, e);
                        }
                    }
                } catch (Exception e) {
                    HeraLog.error("job log flush exception:{}", e.toString());
                }

            }
        },0, 5, TimeUnit.SECONDS);
    }

    /**
     * 机器启动spring-boot时，worker向主节点发起netty请求连接，成功之后，worker异步获取channel,并设置在work context中
     *
     * @param host
     * @throws Exception
     */
    public synchronized void connect(String host) throws Exception {
        if (workContext.getServerChannel() != null) {
            if (workContext.getServerHost().equals(host)) {
                return;
            } else {
                workContext.getServerChannel().close();
                workContext.setServerChannel(null);
            }
        }
        workContext.setServerHost(host);
        CountDownLatch latch = new CountDownLatch(1);
        ChannelFutureListener futureListener = (future) -> {
            try {
                if (future.isSuccess()) {
                    workContext.setServerChannel(future.channel());
                    SocketLog.info(workContext.getServerChannel().toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
            }
        };
        ChannelFuture connectFuture = bootstrap.connect(new InetSocketAddress(host, HeraGlobalEnvironment.getConnectPort()));
        connectFuture.addListener(futureListener);
        if (!latch.await(10, TimeUnit.SECONDS)) {
            connectFuture.removeListener(futureListener);
            connectFuture.cancel(true);
            throw new ExecutionException(new TimeoutException("connect server consumption of 2 seconds"));
        }
        if (!connectFuture.isSuccess()) {
            throw new RuntimeException("connect server failed " + host,
                    connectFuture.cause());
        }
        SocketLog.info("connect server success");
    }

    /**
     * 取消执行开发中心任务
     *
     * @param debugId
     */
    public void cancelDebugJob(String debugId) {
        Job job = workContext.getDebugRunning().get(debugId);
        job.cancel();
        workContext.getDebugRunning().remove(debugId);

        HeraDebugHistoryVo history = job.getJobContext().getDebugHistory();
        history.setEndTime(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        history.setStatus(StatusEnum.FAILED);
        workContext.getDebugHistoryService().update(BeanConvertUtils.convert(history));
        history.getLog().appendHera("任务被取消");
        workContext.getDebugHistoryService().update(BeanConvertUtils.convert(history));


    }

    /**
     * 取消手动执行的任务
     *
     * @param historyId
     */
    public void cancelManualJob(String historyId) {
        Job job = workContext.getManualRunning().get(historyId);
        workContext.getManualRunning().remove(historyId);
        job.cancel();

        HeraJobHistoryVo history = job.getJobContext().getHeraJobHistory();
        history.setEndTime(new Date());
        String illustrate = history.getIllustrate();
        if (illustrate != null && illustrate.trim().length() > 0) {
            history.setIllustrate(illustrate + "；手动取消该任务");
        } else {
            history.setIllustrate("手动取消该任务");
        }
        history.setStatusEnum(StatusEnum.FAILED);
        history.getLog().appendHera("任务被取消");
        workContext.getJobHistoryService().updateHeraJobHistoryLogAndStatus(BeanConvertUtils.convert(history));

    }

    /**
     * 取消自动调度执行的任务
     *
     * @param actionId
     */
    public void cancelScheduleJob(String actionId) {
        Job job = workContext.getRunning().get(actionId);
        workContext.getRunning().remove(actionId);
        job.cancel();

        HeraJobHistoryVo history = job.getJobContext().getHeraJobHistory();
        history.setEndTime(new Date());
        String illustrate = history.getIllustrate();
        if (illustrate != null && illustrate.trim().length() > 0) {
            history.setIllustrate(illustrate + "；手动取消该任务");
        } else {
            history.setIllustrate("手动取消该任务");
        }
        history.setStatusEnum(StatusEnum.FAILED);
        workContext.getJobHistoryService().update(BeanConvertUtils.convert(history));
        history.getLog().appendHera("任务被取消");
        workContext.getJobHistoryService().updateHeraJobHistoryLog(BeanConvertUtils.convert(history));

    }


    /**
     * 页面开发中心发动执行脚本时，发起请求，
     *
     * @param kind
     * @param id
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public void executeJobFromWeb(ExecuteKind kind, String id) throws ExecutionException, InterruptedException {
        RpcWebResponse.WebResponse response = WorkerHandleWebRequest.handleWebExecute(workContext, kind, id).get();
        if (response.getStatus() == ResponseStatus.Status.ERROR) {
            SocketLog.error(response.getErrorText());
        }
    }

    public String cancelJobFromWeb(ExecuteKind kind, String id) throws ExecutionException, InterruptedException {
        RpcWebResponse.WebResponse webResponse = WorkerHandleWebRequest.handleCancel(workContext, kind, id).get();
        if (webResponse.getStatus() == ResponseStatus.Status.ERROR) {
            SocketLog.error(webResponse.getErrorText());
            return webResponse.getErrorText();
        }
        return "取消任务成功";
    }

    public void updateJobFromWeb(String jobId) throws ExecutionException, InterruptedException {
        RpcWebResponse.WebResponse webResponse = WorkerHandleWebRequest.handleUpdate(workContext, jobId).get();
        if (webResponse.getStatus() == ResponseStatus.Status.ERROR) {
            SocketLog.error(webResponse.getErrorText());
        }
    }

    public String generateActionFromWeb(ExecuteKind kind, String id) throws ExecutionException, InterruptedException {
        RpcWebResponse.WebResponse response = WorkerHandleWebRequest.handleWebAction(workContext, kind, id).get();
        if (response.getStatus() == ResponseStatus.Status.ERROR) {
            SocketLog.error("generate action error");
            return "生成版本失败";
        }
        return "生成版本成功";
    }

    public Map<String, HeartBeatInfo> getJobQueueInfoFromWeb() throws ExecutionException, InterruptedException, InvalidProtocolBufferException {
        RpcWebResponse.WebResponse response = WorkerHandleWebRequest.getJobQueueInfoFromMaster(workContext).get();
        if (response.getStatus() == ResponseStatus.Status.ERROR) {
            SocketLog.error("获取心跳信息失败:{}", response.getErrorText());
            return null;
        }
        Map<String, HeartBeatMessage> map = AllHeartBeatInfoMessage.parseFrom(response.getBody()).getValuesMap();
        Map<String, HeartBeatInfo> infoMap = new HashMap<>(map.size());
        for (Map.Entry<String, HeartBeatMessage> entry : map.entrySet()) {
            HeartBeatMessage beatMessage = entry.getValue();
            infoMap.put(entry.getKey(), HeartBeatInfo.builder()
                    .cpuLoadPerCore(beatMessage.getCpuLoadPerCore())
                    .debugRunning(beatMessage.getDebugRunningsList())
                    .manualRunning(beatMessage.getManualRunningsList())
                    .running(beatMessage.getRunningsList())
                    .memRate(beatMessage.getMemRate())
                    .memTotal(beatMessage.getMemTotal())
                    .host(beatMessage.getHost())
                    .cores(beatMessage.getCores())
                    .timestamp(beatMessage.getTimestamp())
                    .date(ActionUtil.getDefaultFormatterDate(new Date(beatMessage.getTimestamp())))
                    .build());
        }

        return infoMap;
    }

}
