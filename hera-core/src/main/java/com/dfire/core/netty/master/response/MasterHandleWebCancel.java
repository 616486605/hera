package com.dfire.core.netty.master.response;

import com.dfire.common.entity.vo.HeraDebugHistoryVo;
import com.dfire.common.enums.TriggerType;
import com.dfire.common.entity.HeraDebugHistory;
import com.dfire.common.entity.vo.HeraJobHistoryVo;
import com.dfire.common.util.BeanConvertUtils;
import com.dfire.common.vo.JobStatus;
import com.dfire.core.message.Protocol.*;
import com.dfire.core.netty.master.MasterContext;
import com.dfire.core.netty.master.MasterWorkHolder;
import com.dfire.core.queue.JobElement;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 下午3:58 2018/5/11
 * @desc
 */
@Slf4j
public class MasterHandleWebCancel {

    public WebResponse handleWebCancel(MasterContext masterContext, WebRequest request) {
        if (request.getEk() == ExecuteKind.ScheduleKind) {
            return handleScheduleCancel(masterContext, request);
        } else if (request.getEk() == ExecuteKind.ManualKind) {
            return handleManualCancel(masterContext, request);
        } else if (request.getEk() == ExecuteKind.DebugKind) {
            return handleDebugCancel(masterContext, request);
        }
        return null;
    }

    private WebResponse handleDebugCancel(MasterContext context, WebRequest request) {
        WebResponse webResponse = null;
        String debugId = request.getId();
        HeraDebugHistoryVo debugHistory = context.getHeraDebugHistoryService().findById(HeraDebugHistory.builder().id(debugId).build());
        for (JobElement element : new ArrayList<JobElement>(context.getDebugQueue())) {
            if (element.getJobId().equals(debugId)) {
                webResponse = WebResponse.newBuilder()
                        .setRid(request.getRid())
                        .setOperate(request.getOperate())
                        .setStatus(Status.OK)
                        .build();
                debugHistory.getLog().appendHera("任务取消");
                context.getHeraDebugHistoryService().update(BeanConvertUtils.convert(debugHistory));
                break;

            }
        }

        for (Channel key : new HashSet<Channel>(context.getWorkMap().keySet())) {
            MasterWorkHolder workHolder = context.getWorkMap().get(key);
            if (workHolder.getDebugRunning().containsKey(debugId)) {
                Future<Response> future = new MasterHandleCancelJob().cancel(context,
                        workHolder.getChannel(), ExecuteKind.DebugKind, debugId);
                workHolder.getDebugRunning().remove(debugId);
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {

                }
                webResponse = WebResponse.newBuilder()
                        .setRid(request.getRid())
                        .setOperate(request.getOperate())
                        .setStatus(Status.OK)
                        .build();

                log.info("send web cancel response, jobId = " + debugId);
            }
        }

        if (webResponse != null) {
            webResponse = WebResponse.newBuilder()
                    .setRid(request.getRid())
                    .setOperate(request.getOperate())
                    .setStatus(Status.ERROR)
                    .setErrorText("Manual任务中找不到匹配的job(" + debugHistory.getId() + "," + debugHistory.getId() + ")，无法执行取消命令")
                    .build();
        }
        debugHistory = context.getHeraDebugHistoryService().findById(HeraDebugHistory.builder().id(debugId).build());
        debugHistory.setEndTime(new Date());
        debugHistory.setStatus(com.dfire.common.enums.Status.FAILED);
        context.getHeraDebugHistoryService().update(BeanConvertUtils.convert(debugHistory));
        return webResponse;


    }

    private WebResponse handleManualCancel(MasterContext context, WebRequest request) {
        WebResponse webResponse = null;
        String historyId = request.getId();
        HeraJobHistoryVo history = context.getHeraJobHistoryService().findJobHistory(historyId);
        String jobId = history.getJobId();
        for (JobElement element : new ArrayList<JobElement>(context.getManualQueue())) {
            if (element.getJobId().equals(historyId)) {
                webResponse = WebResponse.newBuilder()
                        .setRid(request.getRid())
                        .setOperate(request.getOperate())
                        .setStatus(Status.OK)
                        .build();
                history.getLog().appendHera("任务取消");
                context.getHeraJobHistoryService().updateHeraJobHistory(BeanConvertUtils.convert(history));
                break;

            }
        }

        if (history.getTriggerType() == TriggerType.MANUAL) {
            for (Channel key : new HashSet<Channel>(context.getWorkMap().keySet())) {
                MasterWorkHolder workHolder = context.getWorkMap().get(key);
                if (workHolder.getManningRunning().containsKey(jobId)) {
                    Future<Response> future = new MasterHandleCancelJob().cancel(context,
                            workHolder.getChannel(), ExecuteKind.ManualKind, historyId);
                    workHolder.getManningRunning().remove(jobId);
                    try {
                        future.get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {

                    }
                    webResponse = WebResponse.newBuilder()
                            .setRid(request.getRid())
                            .setOperate(request.getOperate())
                            .setStatus(Status.OK)
                            .build();

                    log.info("send web cancel response, jobId = " + jobId);
                }
            }
        }

        if (webResponse != null) {
            webResponse = WebResponse.newBuilder()
                    .setRid(request.getRid())
                    .setOperate(request.getOperate())
                    .setStatus(Status.ERROR)
                    .setErrorText("Manual任务中找不到匹配的job(" + history.getJobId() + "," + history.getId() + ")，无法执行取消命令")
                    .build();
        }
        history = context.getHeraJobHistoryService().findJobHistory(historyId);
        history.setEndTime(new Date());
        history.setStatus(com.dfire.common.enums.Status.FAILED);
        context.getHeraJobHistoryService().updateHeraJobHistory(BeanConvertUtils.convert(history));
        return webResponse;

    }

    private WebResponse handleScheduleCancel(MasterContext context, WebRequest request) {
        WebResponse webResponse = null;
        String historyId = request.getId();
        HeraJobHistoryVo history = context.getHeraJobHistoryService().findJobHistory(historyId);
        String jobId = history.getJobId();
        for (JobElement element : new ArrayList<JobElement>(context.getScheduleQueue())) {
            if (element.getJobId().equals(historyId)) {
                webResponse = WebResponse.newBuilder()
                        .setRid(request.getRid())
                        .setOperate(request.getOperate())
                        .setStatus(Status.OK)
                        .build();
                history.getLog().appendHera("任务取消");
                context.getHeraJobHistoryService().updateHeraJobHistory(BeanConvertUtils.convert(history));
                break;

            }
        }

        for (Channel key : new HashSet<Channel>(context.getWorkMap().keySet())) {
            MasterWorkHolder workHolder = context.getWorkMap().get(key);
            if (workHolder.getRunning().containsKey(jobId)) {
                Future<Response> future = new MasterHandleCancelJob().cancel(context,
                        workHolder.getChannel(), ExecuteKind.ScheduleKind, historyId);
                workHolder.getRunning().remove(jobId);
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {

                }
                webResponse = WebResponse.newBuilder()
                        .setRid(request.getRid())
                        .setOperate(request.getOperate())
                        .setStatus(Status.OK)
                        .build();

                log.info("send web cancel response, jobId = " + jobId);
            }
        }

        if (webResponse != null) {
            JobStatus jobStatus = context.getHeraJobActionService().findJobStatus(jobId);
            jobStatus.setStatus(com.dfire.common.enums.Status.WAIT);
            jobStatus.setHistoryId(null);
            context.getHeraJobHistoryService().updateJobStatus(jobStatus);
        }
        history = context.getHeraJobHistoryService().findJobHistory(historyId);
        history.setEndTime(new Date());
        history.setStatus(com.dfire.common.enums.Status.FAILED);
        context.getHeraJobHistoryService().updateHeraJobHistory(BeanConvertUtils.convert(history));
        return webResponse;
    }


}