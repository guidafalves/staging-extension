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
package org.exoplatform.management.ecmadmin.operations.queries;

import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.container.xml.ComponentPlugin;
import org.exoplatform.container.xml.Configuration;
import org.exoplatform.container.xml.ExternalComponentPlugins;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.container.xml.ObjectParameter;
import org.exoplatform.management.common.AbstractOperationHandler;
import org.exoplatform.services.cms.queries.QueryService;
import org.exoplatform.services.cms.queries.impl.QueryData;
import org.exoplatform.services.cms.queries.impl.QueryPlugin;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserStatus;
import org.exoplatform.services.wcm.utils.WCMCoreUtils;
import org.gatein.management.api.exceptions.OperationException;
import org.gatein.management.api.operation.OperationContext;
import org.gatein.management.api.operation.OperationNames;
import org.gatein.management.api.operation.ResultHandler;
import org.gatein.management.api.operation.model.ExportResourceModel;
import org.gatein.management.api.operation.model.ExportTask;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Value;
import javax.jcr.query.Query;

/**
 * The Class QueriesExportResource.
 *
 * @author <a href="mailto:thomas.delhomenie@exoplatform.com">Thomas
 *         Delhoménie</a>
 * @version $Revision$
 */
public class QueriesExportResource extends AbstractOperationHandler {

  /**
   * {@inheritDoc}
   */
  @Override
  public void execute(OperationContext operationContext, ResultHandler resultHandler) throws OperationException {
    try {
      QueryService queryService = operationContext.getRuntimeContext().getRuntimeComponent(QueryService.class);
      OrganizationService organizationService = operationContext.getRuntimeContext().getRuntimeComponent(OrganizationService.class);

      List<ExportTask> exportTasks = new ArrayList<ExportTask>();

      List<String> filters = operationContext.getAttributes().getValues("filter");

      // shared queries
      List<Node> sharedQueries = queryService.getSharedQueries(WCMCoreUtils.getSystemSessionProvider());

      Configuration configurationSharedQueries = new Configuration();
      if (sharedQueries != null && !sharedQueries.isEmpty()) {
        ExternalComponentPlugins externalComponentPlugins = new ExternalComponentPlugins();
        externalComponentPlugins.setTargetComponent(QueryService.class.getName());
        ArrayList<ComponentPlugin> componentPluginsList = new ArrayList<ComponentPlugin>();
        externalComponentPlugins.setComponentPlugins(componentPluginsList);

        ComponentPlugin queriesComponentPlugin = new ComponentPlugin();
        queriesComponentPlugin.setName("query.plugin");
        queriesComponentPlugin.setSetMethod("setQueryPlugin");
        queriesComponentPlugin.setType(QueryPlugin.class.getName());

        InitParams queriesPluginInitParams = new InitParams();
        queriesComponentPlugin.setInitParams(queriesPluginInitParams);
        componentPluginsList.add(queriesComponentPlugin);

        // Queries API returns Node object instead of QueryData, so we need
        // to convert them...
        for (Node sharedQueryNode : sharedQueries) {
          String queryName = null;
          if (sharedQueryNode.hasProperty("exo:name")) {
            queryName = sharedQueryNode.getProperty("exo:name").getString();
          } else {
            queryName = sharedQueryNode.getName();
          }

          if (!filters.isEmpty() && !filters.contains(queryName)) {
            continue;
          }

          QueryData queryData = new QueryData();
          queryData.setName(queryName);
          queryData.setStatement(sharedQueryNode.getProperty("jcr:statement").getString());
          if (sharedQueryNode.hasProperty("jcr:language")) {
            queryData.setLanguage(sharedQueryNode.getProperty("jcr:language").getString());
          }
          if (sharedQueryNode.hasProperty("exo:cachedResult")) {
            queryData.setCacheResult(sharedQueryNode.getProperty("exo:cachedResult").getBoolean());
          }
          if (sharedQueryNode.hasProperty("exo:accessPermissions")) {
            Value[] permissionsValues = sharedQueryNode.getProperty("exo:accessPermissions").getValues();
            List<String> permissions = new ArrayList<String>();
            for (Value permissionValue : permissionsValues) {
              permissions.add(permissionValue.getString());
            }
            queryData.setPermissions(permissions);
          }

          ObjectParameter objectParam = new ObjectParameter();
          objectParam.setName(queryData.getName());
          objectParam.setObject(queryData);
          queriesPluginInitParams.addParam(objectParam);
        }
        configurationSharedQueries.addExternalComponentPlugins(externalComponentPlugins);
        exportTasks.add(new QueriesExportTask(configurationSharedQueries, null));
      }

      User[] users = null;
      if (filters.isEmpty()) {
        // users queries
        ListAccess<User> usersListAccess = organizationService.getUserHandler().findAllUsers();
        users = usersListAccess.load(0, usersListAccess.getSize());
      } else {
        int i = 0;
        users = new User[filters.size()];
        for (String userQuery : filters) {
          String[] userQueryParts = userQuery.split("/");
          if (userQueryParts.length == 2) {
            users[i++] = organizationService.getUserHandler().findUserByName(userQueryParts[0], UserStatus.ANY);
          }
        }
      }

      for (User user : users) {
        if (user == null) {
          continue;
        }
        List<Query> userQueries = queryService.getQueries(user.getUserName(), WCMCoreUtils.getSystemSessionProvider());

        if (userQueries != null && !userQueries.isEmpty()) {
          Configuration configurationUserQueries = new Configuration();

          ExternalComponentPlugins userQueriesExternalComponentPlugins = new ExternalComponentPlugins();
          userQueriesExternalComponentPlugins.setTargetComponent(QueryService.class.getName());
          ArrayList<ComponentPlugin> userQueriesComponentPluginsList = new ArrayList<ComponentPlugin>();
          userQueriesExternalComponentPlugins.setComponentPlugins(userQueriesComponentPluginsList);

          ComponentPlugin userQueriesComponentPlugin = new ComponentPlugin();
          userQueriesComponentPlugin.setName("query.plugin");
          userQueriesComponentPlugin.setSetMethod("setQueryPlugin");
          userQueriesComponentPlugin.setType(QueryPlugin.class.getName());

          InitParams userQueriesPluginInitParams = new InitParams();
          userQueriesComponentPlugin.setInitParams(userQueriesPluginInitParams);
          userQueriesComponentPluginsList.add(userQueriesComponentPlugin);

          for (Query query : userQueries) {
            String queryPath = query.getStoredQueryPath();
            String userQueryName = queryPath.substring(queryPath.lastIndexOf("/") + 1);
            if (!filters.isEmpty() && !filters.contains(user.getUserName() + "/" + userQueryName)) {
              continue;
            }

            QueryData queryData = new QueryData();
            queryData.setName(userQueryName);
            queryData.setStatement(query.getStatement());
            queryData.setLanguage(query.getLanguage());
            queryData.setCacheResult(false);
            // no permissions are set on users' queries
            queryData.setPermissions(new ArrayList<String>());

            ObjectParameter objectParam = new ObjectParameter();
            objectParam.setName(queryData.getName());
            objectParam.setObject(queryData);
            userQueriesPluginInitParams.addParam(objectParam);
          }

          configurationUserQueries.addExternalComponentPlugins(userQueriesExternalComponentPlugins);
          exportTasks.add(new QueriesExportTask(configurationUserQueries, user.getUserName()));
        }
      }

      resultHandler.completed(new ExportResourceModel(exportTasks));
    } catch (Exception e) {
      throw new OperationException(OperationNames.EXPORT_RESOURCE, "Unable to export JCR stored Queries", e);
    }
  }
}
