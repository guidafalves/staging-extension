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
package org.exoplatform.management.mop.operations.navigation;

import org.exoplatform.portal.mop.SiteKey;
import org.exoplatform.portal.mop.navigation.NavigationContext;
import org.exoplatform.portal.mop.navigation.NavigationService;
import org.exoplatform.portal.mop.navigation.NodeContext;
import org.gatein.management.api.exceptions.ResourceNotFoundException;
import org.gatein.management.api.operation.OperationContext;
import org.gatein.management.api.operation.ResultHandler;
import org.gatein.management.api.operation.model.ReadResourceModel;
import org.gatein.mop.api.workspace.Navigation;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The Class NavigationReadResource.
 *
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 * @version $Revision$
 */
public class NavigationReadResource extends AbstractNavigationOperationHandler {
  
  /**
   * {@inheritDoc}
   */
  @Override
  protected void execute(OperationContext operationContext, ResultHandler resultHandler, Navigation defaultNavigation) {
    SiteKey siteKey = getSiteKey(defaultNavigation.getSite());
    String navUri = operationContext.getAddress().resolvePathTemplate("nav-uri");

    NavigationService navigationService = operationContext.getRuntimeContext().getRuntimeComponent(NavigationService.class);
    NavigationContext navigation = navigationService.loadNavigation(siteKey);

    Set<String> children = new LinkedHashSet<String>();

    NodeContext<NodeContext<?>> node = NavigationUtils.loadNode(navigationService, navigation, navUri);
    if (node == null) {
      throw new ResourceNotFoundException("Navigation node not found for navigation uri '" + navUri + "'");
    }

    for (NodeContext<?> child : node.getNodes()) {
      children.add(child.getName());
    }

    ReadResourceModel model = new ReadResourceModel("Navigation nodes available at this resource.", children);
    resultHandler.completed(model);
  }
}
