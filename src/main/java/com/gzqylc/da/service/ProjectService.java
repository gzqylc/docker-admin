package com.gzqylc.da.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.PushImageCmd;
import com.google.common.collect.Sets;
import com.gzqylc.da.dao.HostDao;
import com.gzqylc.da.dao.RegistryDao;
import com.gzqylc.da.entity.*;
import com.gzqylc.da.web.logger.PipelineLogger;
import com.gzqylc.da.service.pipe.callback.BuildImageResultCallback;
import com.gzqylc.da.service.pipe.callback.PushImageCallback;
import com.gzqylc.utils.DockerTool;
import com.gzqylc.lang.web.base.BaseService;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.File;
import java.io.IOException;
import java.util.*;

@Service
public class ProjectService extends BaseService<Project> {


    @Autowired
    RegistryDao registryDao;

    @Autowired
    HostDao hostDao;

    public void saveProject(Project project) {
        Registry registry = registryDao.findOne(project.getRegistry());

        project.setImageUrl(registry.getHost() + "/" + registry.getNamespace() + "/" + project.getName());
        if (project.getBuildConfig() == null) {
            project.setBuildConfig(new App.BuildConfig());
        }

        project = super.save(project);
    }


    public void buildImage(String pipelineId, Pipeline.PipeBuildConfig cfg) throws GitAPIException, InterruptedException, IOException {
        PipelineLogger logger = PipelineLogger.getLogger(pipelineId);
        logger.info("开始构建镜像");
        // 获取代码
        File workDir = new File("/tmp/" + UUID.randomUUID());
        logger.info("工作目录为 {}", workDir.getAbsolutePath());
        logger.info("获取代码 git clone {}", cfg.getGitUrl());


        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider(cfg.getGitUsername(), cfg.getGitPassword());

        if (workDir.exists()) {
            boolean delete = workDir.delete();
            Assert.state(delete, "删除文件失败");
        }

        Git git = Git.cloneRepository()
                .setURI(cfg.getGitUrl())
                .setNoTags()
                .setCredentialsProvider(provider)
                .setDirectory(workDir)
                .call();

        String commitMsg = git.log().call().iterator().next().getFullMessage();
        logger.info("git log : {}", commitMsg);
        git.close();


        logger.info("代码获取完毕, 共 {} M", FileUtils.sizeOfDirectory(workDir) / 1024 / 1024);


        // 构建
        logger.info("构建镜像");

        DockerClient dockerClient;

        String buildHost = cfg.getBuildHost();
        if (buildHost == null) {
            logger.info("未配置构建主机，将使用本机构建");
            dockerClient = DockerTool.getLocalClient(cfg.getRegistryHost(),
                    cfg.getRegistryUsername(),
                    cfg.getRegistryPassword());

        } else {
            Host host = hostDao.findOne(buildHost);
            logger.info("构建主机:{}", host);
            if (host.getDockerId() != null) {
                dockerClient = DockerTool.getRemoteClient(host.getDockerId(), cfg.getRegistryHost(),
                        cfg.getRegistryUsername(),
                        cfg.getRegistryPassword());
            } else {
                dockerClient = DockerTool.getLocalClient(cfg.getRegistryHost(),
                        cfg.getRegistryUsername(),
                        cfg.getRegistryPassword());
            }

        }

        logger.info("使用本地主机构建");


        String imageUrl = cfg.getImageUrl();
        String latestTag = imageUrl + ":latest";
        String commitTag = imageUrl + ":" + cfg.getBranch();
        Set<String> tags = Sets.newHashSet(latestTag, commitTag);


        File buildDir = new File(workDir, cfg.getContext());

        BuildImageCmd buildImageCmd = dockerClient.buildImageCmd(buildDir).withTags(tags);
        boolean useCache = cfg.isUseCache();
        logger.info("是否使用缓存  {}", useCache);
        buildImageCmd.withNoCache(!useCache);

        String imageId = buildImageCmd.exec(new BuildImageResultCallback(logger)).awaitImageId();
        logger.info("镜像构建结束 imageId={}", imageId);

        // 推送
        logger.info("推送镜像");
        for (String tag : tags) {
            PushImageCmd pushImageCmd = dockerClient.pushImageCmd(tag);
            pushImageCmd.exec(new PushImageCallback(logger)).awaitCompletion();
        }

        dockerClient.close();


        logger.info("构建阶段结束");
    }
}
