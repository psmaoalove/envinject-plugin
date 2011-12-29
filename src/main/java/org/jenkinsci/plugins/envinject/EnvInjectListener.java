package org.jenkinsci.plugins.envinject;

import hudson.*;
import hudson.matrix.MatrixRun;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.util.LogTaskListener;
import org.jenkinsci.plugins.envinject.service.BuildCauseRetriever;
import org.jenkinsci.plugins.envinject.service.EnvInjectActionSetter;
import org.jenkinsci.plugins.envinject.service.EnvInjectEnvVars;
import org.jenkinsci.plugins.envinject.service.EnvInjectVariableGetter;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Gregory Boissinot
 */
@Extension
public class EnvInjectListener extends RunListener<Run> implements Serializable {

    private static Logger LOG = Logger.getLogger(EnvInjectListener.class.getName());

    @Override
    public Environment setUpEnvironment(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {

        EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
        if (!isMatrixRun(build) && variableGetter.isEnvInjectJobPropertyActive(build.getParent())) {

            EnvInjectLogger logger = new EnvInjectLogger(listener);
            logger.info("Preparing an environment for the job.");
            try {

                EnvInjectJobProperty envInjectJobProperty = variableGetter.getEnvInjectJobProperty(build.getParent());
                assert envInjectJobProperty != null;
                EnvInjectJobPropertyInfo info = envInjectJobProperty.getInfo();
                assert envInjectJobProperty != null && envInjectJobProperty.isOn();

                Map<String, String> infraEnvVarsNode = new LinkedHashMap<String, String>();
                Map<String, String> infraEnvVarsMaster = new LinkedHashMap<String, String>();

                //Add Jenkins System variables
                if (envInjectJobProperty.isKeepJenkinsSystemVariables()) {
                    logger.info("Jenkins system variables are kept.");
                    infraEnvVarsNode.putAll(variableGetter.getJenkinsSystemVariablesCurrentNode(build));
                    infraEnvVarsMaster.putAll(getJenkinsSystemVariablesMaster(build));
                }

                //Add build variables (such as parameter variables).
                if (envInjectJobProperty.isKeepBuildVariables()) {
                    logger.info("Jenkins build variables are kept.");
                    Map<String, String> buildVariables = variableGetter.getBuildVariables(build, logger);
                    infraEnvVarsNode.putAll(buildVariables);
                    infraEnvVarsMaster.putAll(buildVariables);
                }

                final FilePath rootPath = getNodeRootPath();
                if (rootPath != null) {

                    EnvInjectEnvVars envInjectEnvVarsService = new EnvInjectEnvVars(logger);
                    final Map<String, String> propertiesVariables = envInjectEnvVarsService.getEnvVarsPropertiesJobProperty(rootPath,
                            logger, info.isLoadFilesFromMaster(),
                            info.getPropertiesFilePath(), info.getPropertiesContent(),
                            infraEnvVarsMaster, infraEnvVarsNode);

                    //Execute script
                    int resultCode = envInjectEnvVarsService.executeScript(info.isLoadFilesFromMaster(),
                            info.getScriptContent(),
                            rootPath, info.getScriptFilePath(), infraEnvVarsMaster, infraEnvVarsNode, propertiesVariables, launcher, listener);
                    if (resultCode != 0) {
                        logger.info(String.format("The exit code is '%s'. Fail the build.", resultCode));
                    }

                    final Map<String, String> resultVariables = envInjectEnvVarsService.getMergedVariables(infraEnvVarsNode, propertiesVariables);

                    //Add an action
                    new EnvInjectActionSetter(rootPath).addEnvVarsToEnvInjectBuildAction(build, resultVariables);

                    return new Environment() {
                        @Override
                        public void buildEnvVars(Map<String, String> env) {
                            env.putAll(resultVariables);
                        }
                    };
                }

            } catch (EnvInjectException envEx) {
                logger.error("SEVERE ERROR occurs: " + envEx.getCause().getMessage());
                throw new Run.RunnerAbortedException();
            } catch (Throwable throwable) {
                logger.error("SEVERE ERROR occurs: " + throwable.getCause().getMessage());
                throw new Run.RunnerAbortedException();
            }
        }

        return new Environment() {
        };
    }

    private boolean isMatrixRun(AbstractBuild build) {
        return build instanceof MatrixRun;
    }


    private Map<String, String> getJenkinsSystemVariablesMaster(AbstractBuild build) throws IOException, InterruptedException {

        Map<String, String> result = new TreeMap<String, String>();
        result.putAll(build.getCharacteristicEnvVars());

        Computer computer = Hudson.getInstance().toComputer();
        //test if there is at least one executor
        if (computer != null) {
            result = computer.getEnvironment().overrideAll(result);
            Node n = computer.getNode();
            if (n != null)
                result.put("NODE_NAME", computer.getName());
            result.put("NODE_LABELS", Util.join(n.getAssignedLabels(), " "));
        }

        String rootUrl = Hudson.getInstance().getRootUrl();
        if (rootUrl != null) {
            result.put("JENKINS_URL", rootUrl);
            result.put("HUDSON_URL", rootUrl); // Legacy compatibility
            result.put("BUILD_URL", rootUrl + build.getUrl());
            result.put("JOB_URL", rootUrl + build.getParent().getUrl());
        }
        result.put("JENKINS_HOME", Hudson.getInstance().getRootDir().getPath());
        result.put("HUDSON_HOME", Hudson.getInstance().getRootDir().getPath());   // legacy compatibility


        EnvVars envVars = new EnvVars();
        for (EnvironmentContributor ec : EnvironmentContributor.all())
            ec.buildEnvironmentFor(build, envVars, new LogTaskListener(LOG, Level.ALL));
        result.putAll(envVars);

        //Global properties
        for (NodeProperty<?> nodeProperty : Hudson.getInstance().getGlobalNodeProperties()) {
            if (nodeProperty instanceof EnvironmentVariablesNodeProperty) {
                EnvironmentVariablesNodeProperty environmentVariablesNodeProperty = (EnvironmentVariablesNodeProperty) nodeProperty;
                result.putAll(environmentVariablesNodeProperty.getEnvVars());
            }
        }

        //Node properties
        if (computer != null) {
            Node node = computer.getNode();
            if (node != null) {
                for (NodeProperty<?> nodeProperty : node.getNodeProperties()) {
                    if (nodeProperty instanceof EnvironmentVariablesNodeProperty) {
                        EnvironmentVariablesNodeProperty environmentVariablesNodeProperty = (EnvironmentVariablesNodeProperty) nodeProperty;
                        result.putAll(environmentVariablesNodeProperty.getEnvVars());
                    }
                }
            }
        }
        return result;
    }


    private Node getNode() {
        Computer computer = Computer.currentComputer();
        if (computer == null) {
            return null;
        }
        return computer.getNode();
    }

    private FilePath getNodeRootPath() {
        Node node = getNode();
        if (node != null) {
            return node.getRootPath();
        }
        return null;
    }

}
