/*
 * Copyright (C) 2003-2017 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.management.service.impl;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.exoplatform.management.service.api.ChromatticService;
import org.exoplatform.management.service.api.ResourceCategory;
import org.exoplatform.management.service.api.ResourceHandler;
import org.exoplatform.management.service.api.SynchronizationService;
import org.exoplatform.management.service.api.TargetServer;
import org.exoplatform.management.service.handler.ResourceHandlerLocator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;
import org.exoplatform.services.security.IdentityConstants;
import org.picocontainer.Startable;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Class SynchronizationServiceImpl.
 */
public class SynchronizationServiceImpl implements SynchronizationService, Startable {

  /** The Constant VARIABLE_PATTERN. */
  private static final Pattern VARIABLE_PATTERN = Pattern.compile("^\\$\\{(.*)\\}$");

  /** The Constant LOG. */
  private static final Log LOG = ExoLogger.getLogger(SynchronizationServiceImpl.class);

  /** The chromattic service. */
  private ChromatticService chromatticService;

  /**
   * Instantiates a new synchronization service impl.
   *
   * @param chromatticService the chromattic service
   */
  public SynchronizationServiceImpl(ChromatticService chromatticService) {
    this.chromatticService = chromatticService;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<TargetServer> getSynchonizationServers() {
    return chromatticService.getSynchonizationServers();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void testServerConnection(TargetServer targetServer) throws Exception {
    String targetServerURL = "http" + (targetServer.isSsl() ? "s://" : "://") + targetServer.getHost() + ":" + targetServer.getPort() + "/rest/private/staging/message/get";
    URL url = new URL(targetServerURL);

    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    String passString = targetServer.getUsername() + ":" + targetServer.getPassword();
    String basicAuth = "Basic " + new String(Base64.encodeBase64(passString.getBytes()));
    conn.setRequestProperty("Authorization", basicAuth);
    int responseCode = conn.getResponseCode();
    if (responseCode != 200) {
      throw new RuntimeException("Could not connect to server: '" + targetServer + "'. HTTP error code = " + responseCode);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addSynchonizationServer(TargetServer targetServer) {
    targetServer.setName(getVariableFromPattern(targetServer.getName()));
    targetServer.setHost(getVariableFromPattern(targetServer.getHost()));
    targetServer.setPort(getVariableFromPattern(targetServer.getPort()));
    targetServer.setUsername(getVariableFromPattern(targetServer.getUsername()));
    targetServer.setPassword(getVariableFromPattern(targetServer.getPassword()));

    chromatticService.addSynchonizationServer(targetServer);
  }

  /**
   * Gets the variable from pattern.
   *
   * @param variable the variable
   * @return the variable from pattern
   */
  private static String getVariableFromPattern(String variable) {
    if (StringUtils.isEmpty(variable)) {
      return null;
    }
    Matcher matcher = VARIABLE_PATTERN.matcher(variable.trim());
    if (matcher.matches()) {
      return matcher.group(1);
    }
    return variable;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void removeSynchonizationServer(TargetServer targetServer) {
    chromatticService.removeSynchonizationServer(targetServer);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void synchronize(List<ResourceCategory> selectedResourcesCategories, TargetServer targetServer) throws Exception {
    for (ResourceCategory selectedResourceCategory : selectedResourcesCategories) {
      // Gets the right resource handler thanks to the Service Locator
      ResourceHandler resourceHandler = ResourceHandlerLocator.getResourceHandler(selectedResourceCategory.getPath());

      if (resourceHandler != null) {
        resourceHandler.synchronize(selectedResourceCategory.getResources(), selectedResourceCategory.getExportOptions(), selectedResourceCategory.getImportOptions(), targetServer);
      } else {
        LOG.error("No handler for " + selectedResourceCategory.getPath());
        throw new Exception("No handler for " + selectedResourceCategory.getPath());
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    String name = System.getProperty("exo.staging.server.default.name");
    String host = System.getProperty("exo.staging.server.default.host");
    String port = System.getProperty("exo.staging.server.default.port");
    String username = System.getProperty("exo.staging.server.default.username");
    String password = System.getProperty("exo.staging.server.default.password");
    String isSSLString = System.getProperty("exo.staging.server.default.isSSL");
    if (!StringUtils.isEmpty(name) && !StringUtils.isEmpty(host) && !StringUtils.isEmpty(port) && !StringUtils.isEmpty(username) && !StringUtils.isEmpty(password)) {
      ConversationState originalState = ConversationState.getCurrent();
      if (originalState == null) {
        ConversationState.setCurrent(new ConversationState(new Identity(IdentityConstants.SYSTEM)));
      }
      try {
        TargetServer server = chromatticService.getServerByName(name);
        if (server != null) {
          LOG.info("Delete server info for: " + name);
          chromatticService.removeSynchonizationServer(server);
        }
        LOG.info("Persist server info for: " + name);
        boolean isSSL = StringUtils.isEmpty(isSSLString) ? false : Boolean.parseBoolean(isSSLString);
        server = new TargetServer(name, host, port, username, password, isSSL);
        addSynchonizationServer(server);
      } finally {
        ConversationState.setCurrent(originalState);
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void stop() {}
}