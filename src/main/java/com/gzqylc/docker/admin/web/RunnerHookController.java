package com.gzqylc.docker.admin.web;

import com.gzqylc.docker.admin.service.PipelineService;
import com.gzqylc.docker.admin.web.logger.FileLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController

public class RunnerHookController {

    public static final String API_LOG = "api/runner_hook/log";
    public static final String API_PIPE_FINISH = "api/runner_hook/pipe_finish";


    @RequestMapping(API_LOG + "/{id}")
    public void log(@PathVariable String id, @RequestBody String msg) throws IOException {
        FileLogger logger = FileLogger.getLogger(id);
        logger.info("远程执行节点：" + msg);
    }

    @RequestMapping(API_PIPE_FINISH + "/{id}/{pipeId}/{result}")
    public void hook(@PathVariable String id, @PathVariable String pipeId, @PathVariable boolean result) throws IOException {
        FileLogger logger = FileLogger.getLogger(id);
        logger.info("接收到hook {},{}, {}", id, pipeId, result);

        pipelineService.notifyPipeFinishAsync(id, pipeId, result);
    }


    @Autowired
    private PipelineService pipelineService;
}
