package fr.treeptik.cloudunit.service.impl;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.inject.Inject;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.spotify.docker.client.messages.Image;
import fr.treeptik.cloudunit.config.DockerConfiguration;
import fr.treeptik.cloudunit.utils.NamingUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.exceptions.ContainerNotFoundException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ContainerInfo;

import fr.treeptik.cloudunit.docker.core.DockerCloudUnitClient;
import fr.treeptik.cloudunit.docker.model.DockerContainer;
import fr.treeptik.cloudunit.enums.RemoteExecAction;
import fr.treeptik.cloudunit.exception.DockerJSONException;
import fr.treeptik.cloudunit.exception.FatalDockerJSONException;
import fr.treeptik.cloudunit.exception.ServiceException;
import fr.treeptik.cloudunit.model.Module;
import fr.treeptik.cloudunit.model.Server;
import fr.treeptik.cloudunit.model.User;
import fr.treeptik.cloudunit.service.DockerService;
import fr.treeptik.cloudunit.utils.ContainerMapper;
import fr.treeptik.cloudunit.utils.ContainerUtils;
import fr.treeptik.cloudunit.utils.FilesUtils;

/**
 * Created by guillaume on 01/08/16.
 */
@Service
public class DockerServiceImpl implements DockerService {

    private Logger logger = LoggerFactory.getLogger(DockerService.class);

    @Inject
    private ContainerMapper containerMapper;

    @Value("#{systemEnvironment['CU_DOMAIN']}")
    private String domainSuffix;
    
    @Value("#{systemEnvironment['http_proxy']}")
    private String httpProxy;

    protected String domain;

    @Inject
    private DockerClient dockerClient;

    @Inject
    private DockerCloudUnitClient dockerCloudUnitClient;

    @Inject
    private DockerConfiguration dockerConfiguration;

    @PostConstruct
    public void init() {
        domain = NamingUtils.getCloudUnitDomain(domainSuffix);
    }

    @Override
    public void createServer(String containerName, Server server, String imagePath, String imageSubType, User user, List<String> envs,
                             boolean createMainVolume, List<String> volumes) throws DockerJSONException, ServiceException {
        if (volumes == null) { volumes = new ArrayList<>(); }
        if (createMainVolume) { dockerCloudUnitClient.createVolume(containerName, "runtime"); }
        volumes.add(containerName + ":/opt/cloudunit:rw");
        List<String> volumesFrom = null;
        if (dockerConfiguration.isAgentPresent()) {
            volumesFrom = Arrays.asList("cu-monitoring-agents");
        }
        logger.info("Volumes to add : " + volumes.toString());
        List<String> args = null;
        if (server.isApplicationServer()) {
            args = new ArrayList<>();
            args.add("run");
            args.add(user.getLogin());
            args.add(user.getPassword());
        }
        if (StringUtils.isNotEmpty(httpProxy)) {
        	if (envs == null) {
        		envs = new ArrayList<>();
        	}
        	envs.add(String.format("http_proxy=%s", httpProxy));
        	envs.add(String.format("https_proxy=%s", httpProxy));
        	envs.add(String.format("ftp_proxy=%s", httpProxy));
        }
        //Map<String, String> ports = new HashMap<>();
        //ports.put("8000/tcp", "");
        DockerContainer container = ContainerUtils.newCreateInstance(containerName, imagePath, imageSubType, volumesFrom, args,
                volumes, envs, null, "skynet", domain);
        dockerCloudUnitClient.createContainer(container);
    }

    @Override
    public Server startServer(String containerName, Server server) throws DockerJSONException {
        DockerContainer container = ContainerUtils.newStartInstance(containerName, null, null, false);
        dockerCloudUnitClient.startContainer(container);
        container = dockerCloudUnitClient.findContainer(container);
        server = containerMapper.mapDockerContainerToServer(container, server);
        return server;
    }

    @Override
    public void stopContainer(String containerName) throws DockerJSONException {
        DockerContainer container = ContainerUtils.newStartInstance(containerName, null, null, false);
        dockerCloudUnitClient.stopContainer(container);
    }

    @Override
    public void killServer(String containerName) throws DockerJSONException {
        DockerContainer container = ContainerUtils.newStartInstance(containerName, null, null, false);
        dockerCloudUnitClient.killContainer(container);
    }

    @Override
    public void removeContainer(String containerName, boolean removeVolume) throws DockerJSONException {
        DockerContainer container = ContainerUtils.newStartInstance(containerName, null, null, false);
        dockerCloudUnitClient.removeContainer(container);
        if (removeVolume) {
            dockerCloudUnitClient.removeVolume(containerName);
        }
    }

    @Override
    public String execCommand(String containerName, String command, boolean privileged) throws FatalDockerJSONException {
        return execCommand(containerName, command, privileged, false);
    }

    @Override
    public void exportContainer(String containerName, final OutputStream outputFileStream) throws DockerException, InterruptedException, IOException {
        IOUtils.copy(dockerClient.exportContainer(containerName), outputFileStream);
    }

    @Override
    public String execCommand(String containerName, String command, boolean privileged, boolean detached)
            throws FatalDockerJSONException {
        final String[] commands = { "bash", "-c", command };
        String execId = null;
        try {
            if (privileged) {
                execId = dockerClient.execCreate(containerName, commands,
                        com.spotify.docker.client.DockerClient.ExecCreateParam.detach(detached),
                        com.spotify.docker.client.DockerClient.ExecCreateParam.attachStdout(),
                        com.spotify.docker.client.DockerClient.ExecCreateParam.attachStderr(),
                        com.spotify.docker.client.DockerClient.ExecCreateParam.user("root")).id();
            } else {
                execId = dockerClient.execCreate(containerName, commands,
                        com.spotify.docker.client.DockerClient.ExecCreateParam.detach(detached),
                        com.spotify.docker.client.DockerClient.ExecCreateParam.attachStdout(),
                        com.spotify.docker.client.DockerClient.ExecCreateParam.attachStderr()).id();
            }
            try (final LogStream stream = dockerClient.execStart(execId)) {
                final String output = stream.readFully();
                logger.debug(output);
                return output;
            }
        } catch (DockerException | InterruptedException e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerName:[").append(containerName).append("]");
            msgError.append(", command:[").append(command).append("]");
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    /**
     * Execute a shell conmmad into a container. Return the output as String
     *
     * @param containerName
     * @param command
     * @return
     */
    @Override
    public String execCommand(String containerName, String command) throws FatalDockerJSONException {
        String output = execCommand(containerName, command, false);
        if (output.contains("Permission denied")) {
            logger.warn("[" + containerName + "] exec command in privileged mode : " + command);
            output = execCommand(containerName, command, true);
        }
        return output;
    }

    @Override
    public Boolean isRunning(String containerName) throws FatalDockerJSONException {
        try {
            final ContainerInfo info = dockerClient.inspectContainer("containerID");
            return info.state().running();
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerName=").append(containerName);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public Boolean exists(String containerName) throws FatalDockerJSONException {
        try {
            if (containerName == null || containerName.isEmpty()) return false;
            if (!containerName.startsWith("/")) containerName = "/" + containerName;
            final String realName = containerName;
            List<List<String>> containers = listContainers();
            return containers.stream().anyMatch(c -> c.contains(realName));
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerName=").append(containerName);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public Boolean isStoppedGracefully(String containerName) throws FatalDockerJSONException {
        try {
            final ContainerInfo info = dockerClient.inspectContainer(containerName);
            boolean exited = info.state().status().equalsIgnoreCase("Exited");
            if (info.state().exitCode() != 0) {
                logger.warn("The container may be brutally stopped. Its exit code is : " + info.state().exitCode());
            }
            return exited;
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerName=").append(containerName);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public List<List<String>> listContainers() throws FatalDockerJSONException {
        List<List<String>> containersId = null;
        try {
            List<Container> containers = dockerClient.listContainers(DockerClient.ListContainersParam.allContainers());
            containersId = containers.stream().map(c -> c.names()).collect(Collectors.toList());
        } catch (DockerException | InterruptedException e) {
            logger.error(e.getMessage());
        }
        return containersId;
    }

    @Override
    @Cacheable(value = "monitoring", key = "#containerName")
    public String getContainerId(String containerName) throws FatalDockerJSONException {
        try {
            final ContainerInfo info = dockerClient.inspectContainer(containerName);
            return info.id();
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerName=").append(containerName);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public String getContainerNameFromId(String id) throws FatalDockerJSONException {
        try {
            final ContainerInfo info = dockerClient.inspectContainer(id);
            return info.name();
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("id=").append(id);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    @Cacheable(value = "env", key = "{#containerName,#variable}")
    public String getEnv(String containerName, String variable) throws FatalDockerJSONException {
        try {
            Optional<String> value = dockerClient.inspectContainer(containerName).config().env().stream()
                    .filter(e -> e.startsWith(variable)).map(s -> s.substring(s.indexOf("=") + 1)).findFirst();
            logger.info("VARIABLE=" + value);
            return (value.orElseThrow(() -> new ServiceException(variable + " is missing into DOCKERFILE.")));
        } catch (ContainerNotFoundException e) {
            throw new FatalDockerJSONException(e.getLocalizedMessage(), e);
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerId=").append(containerName);
            msgError.append("variable=").append(variable);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public void addEnv(String containerId, String key, String value) throws FatalDockerJSONException {
        try {
            Map<String, String> kvStore = new HashMap<String, String>() {
                private static final long serialVersionUID = 1L;
                {
                    put("CU_KEY", key);
                    put("CU_VALUE", value);
                }
            };
            execCommand(containerId, RemoteExecAction.ADD_ENV.getCommand(kvStore), true);
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerId=").append(containerId);
            msgError.append(",key=").append(key);
            msgError.append(",value=").append(value);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public int getFileFromContainer(String containerId, String path, OutputStream outputStream)
            throws FatalDockerJSONException {
        try {
            InputStream inputStream = dockerClient.archiveContainer(containerId, path);
            FilesUtils.unTar(inputStream, outputStream);
            int size = inputStream.available();
            return size;
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerId=").append(containerId);
            msgError.append("path=").append(path);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public void sendFileToContainer(String containerId, String localPathFile, String originalName, String filePath)
            throws FatalDockerJSONException {
        try {
            Path path = Paths.get(localPathFile);
            dockerClient.copyToContainer(path, containerId, filePath);
        } catch (Exception e) {
            StringBuilder msgError = new StringBuilder();
            msgError.append("containerId=").append(containerId);
            msgError.append("localPathFile=").append(localPathFile);
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public void createModule(String containerName, Module module, String imagePath, User user, List<String> envs,
            boolean createMainVolume, List<String> volumes) throws DockerJSONException {
        if (createMainVolume) {
            dockerCloudUnitClient.createVolume(containerName, "runtime");
        }
        volumes.add(containerName + ":/opt/cloudunit:rw");
        logger.info("Volumes to add : " + volumes.toString());
        List<String> volumesFrom = null;
        if (dockerConfiguration.isAgentPresent()) {
            volumesFrom = Arrays.asList("cu-monitoring-agents");
        }
        // map ports
        Map<String, String> ports = module.getPorts().stream()
                .filter(p -> p.getOpened())
                .collect(Collectors.toMap(
                        p -> String.format("%s/tcp", p.getContainerValue()),
                        p -> p.getHostValue()));
        
        if (StringUtils.isNotEmpty(httpProxy)) {
        	if (envs == null) {
        		envs = new ArrayList<>();
        	}
        	envs.add(String.format("http_proxy=%s", httpProxy));
        	envs.add(String.format("https_proxy=%s", httpProxy));
        	envs.add(String.format("ftp_proxy=%s", httpProxy));
        }
        DockerContainer container = ContainerUtils.newCreateInstance(containerName, imagePath, null, volumesFrom, null, volumes,
                envs, ports, "skynet", domain);
        dockerCloudUnitClient.createContainer(container);
    }

    @Override
    public Module startModule(String containerName, Module module) throws DockerJSONException {
        DockerContainer container = ContainerUtils.newStartInstance(containerName, null, null,
                null);
        dockerCloudUnitClient.startContainer(container);
        container = dockerCloudUnitClient.findContainer(container);
        module = containerMapper.mapDockerContainerToModule(container, module,
                getEnv(container.getName(), "CU_MODULE_PORT"));
        return module;
    }

    @Override
    public String logs(String container) throws DockerJSONException {
        try {
            LogStream stream = dockerClient.logs(container, DockerClient.LogsParam.stdout(), DockerClient.LogsParam.stderr());
            String logs = stream.readFully();
            if (logger.isDebugEnabled()) { logger.debug(logs); }
            return logs;
        } catch (Exception e) {
            logger.error(container, e);
            return null;
        }
    }

    @Override
    public void pullImage(String imageName) throws FatalDockerJSONException {
        try {
            this.dockerClient.pull(imageName+":latest");
        } catch (DockerException | InterruptedException e) {
            StringBuilder msgError = new StringBuilder();
            throw new FatalDockerJSONException(msgError.toString(), e);
        }
    }

    @Override
    public void deleteImage(String imageName) throws ServiceException {
        try {
            this.dockerClient.removeImage(imageName);
        } catch (DockerException | InterruptedException e) {
            StringBuilder msgError = new StringBuilder();
            throw new ServiceException("Cannot delete image : " + imageName + ", maybe used.", e);
        }
    }

    @Override
    public List<String> listImages() throws ServiceException {
        List<String> imagesId = new ArrayList<>();

        try {
            List<Image> images = dockerClient.listImages(DockerClient.ListImagesFilterParam.withLabel("origin", "application"));
            ImmutableList<String> currentTags = null;
            for (Image image: images) {
                currentTags = image.repoTags().asList();
                for (String tag: currentTags) {
                    imagesId.add(tag);
                }
            }
        } catch (DockerException | InterruptedException e) {
            logger.error(e.getMessage());
        }

        return imagesId;
    }

}
