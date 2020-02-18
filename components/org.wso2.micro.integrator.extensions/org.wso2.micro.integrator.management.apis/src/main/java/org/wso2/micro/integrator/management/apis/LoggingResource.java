/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.micro.integrator.management.apis;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.PropertiesConfigurationLayout;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.json.JSONObject;
import org.wso2.carbon.utils.ServerConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class LoggingResource extends ApiResource {

    private static final Log log = LogFactory.getLog(LoggingResource.class);

    private static final Level[] logLevels = new Level[]{Level.OFF, Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR, Level.FATAL};

    private String carbonHome = System.getProperty(ServerConstants.CARBON_HOME);
    private String filePath = carbonHome + File.separator + "conf" + File.separator + "log4j2.properties";
    private JSONObject jsonBody = new JSONObject();

    public LoggingResource(String urlTemplate) {
        super(urlTemplate);
    }

    @Override
    public Set<String> getMethods() {

        Set<String> methods = new HashSet<>();
        methods.add(Constants.HTTP_GET);
        methods.add(Constants.HTTP_METHOD_PATCH);
        return methods;
    }

    @Override
    public boolean invoke(MessageContext messageContext) {

        buildMessage(messageContext);

        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();

        axis2MessageContext.removeProperty(Constants.NO_ENTITY_BODY);

        JSONObject jsonPayload = new JSONObject(JsonUtil.jsonPayloadToString(axis2MessageContext));

        String logLevel;
        String loggerName;
        JSONObject jsonBody;

        String httpMethod = axis2MessageContext.getProperty("HTTP_METHOD").toString();

        if (httpMethod.equals(Constants.HTTP_GET)) {
            String param = Utils.getQueryParameter(messageContext, Constants.LOGGER_NAME);
            if (Objects.nonNull(param)) {
                jsonBody = getLoggerData(axis2MessageContext, param);
            } else {
                // 400-Bad Request loggerName is missing
                jsonBody = Utils.createJsonErrorObject("Logger Name is missing");
                axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
            }
        } else {
            if (jsonPayload.has(Constants.LOGGING_LEVEL)) {
                logLevel = jsonPayload.getString(Constants.LOGGING_LEVEL);
                if (!isALogLevel(logLevel)) {
                    // 400-Bad Request Invalid loggingLevel
                    jsonBody = Utils.createJsonErrorObject("Invalid log level " + logLevel);
                    axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
                } else {
                    if (jsonPayload.has(Constants.LOGGER_NAME)) {
                        loggerName = jsonPayload.getString(Constants.LOGGER_NAME);
                        // update root and specific logger
                        jsonBody = updateLoggerData(axis2MessageContext, loggerName, logLevel);
                    } else {
                        // 400-Bad Request logger name is missing
                        jsonBody = Utils.createJsonErrorObject("Logger name is missing");
                        axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
                    }
                }
            } else {
                // 400-Bad Request logLevel is missing
                jsonBody = Utils.createJsonErrorObject("Log level is missing");
                axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
            }
        }
        Utils.setJsonPayLoad(axis2MessageContext, jsonBody);
        return true;
    }

    private JSONObject updateLoggerData(org.apache.axis2.context.MessageContext axis2MessageContext, String loggerName,
                                        String logLevel) {

        File log4j2PropertiesFile = new File(filePath);
        String loggers = Utils.getProperty(log4j2PropertiesFile, "loggers");

        PropertiesConfiguration config = new PropertiesConfiguration();
        PropertiesConfigurationLayout layout = new PropertiesConfigurationLayout(config);

        try {
            layout.load(new InputStreamReader(new FileInputStream(log4j2PropertiesFile)));
            if (loggerName.equals(Constants.ROOT_LOGGER)) {
                config.setProperty(loggerName + ".level", logLevel);
                layout.save(new FileWriter(filePath, false));
                Utils.updateLoggingConfiguration();
                jsonBody.put(Constants.MESSAGE, "Successfully updated log level of rootLogger " + logLevel);
            } else {
                if (loggers.contains(loggerName)) {
                    config.setProperty("logger." + loggerName + ".level", logLevel);
                    layout.save(new FileWriter(filePath, false));
                    Utils.updateLoggingConfiguration();
                    jsonBody.put(Constants.MESSAGE, "Successfully updated log level of logger " + loggerName
                            + " to " + logLevel);
                } else {
                    log.error("Specified logger " + loggerName + " is not found");
                    jsonBody = Utils.createJsonErrorObject("Invalid logger " + loggerName);
                    axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
                }
            }
        } catch (ConfigurationException | IOException e) {
            log.error("Exception while updating logger data " + e.getMessage());
            jsonBody = Utils.createJsonErrorObject("Error updating logger " + loggerName);
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
        }
        return jsonBody;
    }

    private JSONObject getLoggerData(org.apache.axis2.context.MessageContext axis2MessageContext, String loggerName) {

        String logLevel;
        String componentName;

        File log4j2PropertiesFile = new File(filePath);
        String logger = Utils.getProperty(log4j2PropertiesFile, "loggers");

        if (loggerName.equals(Constants.ROOT_LOGGER)) {
            componentName = "Not available for rootLogger";
            logLevel = Utils.getProperty(log4j2PropertiesFile, loggerName + ".level");
        } else if (logger.contains(loggerName)) {
            componentName = Utils.getProperty(log4j2PropertiesFile, "logger." + loggerName + ".name");
            logLevel = Utils.getProperty(log4j2PropertiesFile, "logger." + loggerName + ".level");
        } else {
            log.error("Specified logger " + loggerName + " is not found");
            jsonBody = Utils.createJsonErrorObject("Invalid logger " + loggerName);
            axis2MessageContext.setProperty(Constants.HTTP_STATUS_CODE, Constants.BAD_REQUEST);
            return jsonBody;
        }

        jsonBody.put(Constants.LOGGER_NAME, loggerName);
        jsonBody.put(Constants.COMPONENT_NAME, componentName);
        jsonBody.put(Constants.LEVEL, logLevel);

        return jsonBody;
    }

    private boolean isALogLevel(String logLevelToTest) {
        boolean returnValue = false;
        for (Level logLevel : logLevels) {
            if (logLevel.toString().equalsIgnoreCase(logLevelToTest))
                returnValue = true;
        }
        return returnValue;
    }
}
