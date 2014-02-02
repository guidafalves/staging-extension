package org.exoplatform.management.content.operations.site.contents;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.query.Query;

import org.apache.commons.lang.StringUtils;
import org.exoplatform.portal.config.DataStorage;
import org.exoplatform.portal.config.model.Application;
import org.exoplatform.portal.config.model.Container;
import org.exoplatform.portal.config.model.ModelObject;
import org.exoplatform.portal.config.model.Page;
import org.exoplatform.portal.config.model.PortalConfig;
import org.exoplatform.portal.mop.SiteType;
import org.exoplatform.portal.mop.page.PageContext;
import org.exoplatform.portal.mop.page.PageService;
import org.exoplatform.portal.pom.spi.portlet.Portlet;
import org.exoplatform.services.cms.templates.TemplateService;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.wcm.core.NodeLocation;
import org.exoplatform.services.wcm.core.WCMConfigurationService;
import org.exoplatform.services.wcm.core.WCMService;
import org.exoplatform.services.wcm.portal.PortalFolderSchemaHandler;
import org.exoplatform.wcm.webui.Utils;
import org.gatein.management.api.PathAddress;
import org.gatein.management.api.exceptions.OperationException;
import org.gatein.management.api.operation.OperationAttributes;
import org.gatein.management.api.operation.OperationContext;
import org.gatein.management.api.operation.OperationHandler;
import org.gatein.management.api.operation.OperationNames;
import org.gatein.management.api.operation.ResultHandler;
import org.gatein.management.api.operation.model.ExportResourceModel;
import org.gatein.management.api.operation.model.ExportTask;

/**
 * @author <a href="mailto:thomas.delhomenie@exoplatform.com">Thomas
 *         Delhoménie</a>
 * @version $Revision$
 */
public class SiteContentsExportResource implements OperationHandler {
  private static final Log log = ExoLogger.getLogger(SiteContentsExportResource.class);

  private static final String FOLDER_PATH = "folderPath";
  private static final String WORKSPACE = "workspace";
  private static final String IDENTIFIER = "nodeIdentifier";
  public static final String FILTER_SEPARATOR = ":";

  private WCMConfigurationService wcmConfigurationService = null;
  private TemplateService templateService = null;
  private RepositoryService repositoryService = null;
  private WCMService wcmService = null;
  private DataStorage dataStorage = null;
  private PageService pageService = null;

  private Map<String, Boolean> isNTRecursiveMap = new HashMap<String, Boolean>();

  private SiteMetaData metaData = null;

  @Override
  public void execute(OperationContext operationContext, ResultHandler resultHandler) throws OperationException {
    try {
      metaData = new SiteMetaData();
      String operationName = operationContext.getOperationName();
      PathAddress address = operationContext.getAddress();
      OperationAttributes attributes = operationContext.getAttributes();

      String siteName = address.resolvePathTemplate("site-name");
      if (siteName == null) {
        throw new OperationException(operationName, "No site name specified.");
      }

      wcmConfigurationService = operationContext.getRuntimeContext().getRuntimeComponent(WCMConfigurationService.class);
      repositoryService = operationContext.getRuntimeContext().getRuntimeComponent(RepositoryService.class);
      templateService = operationContext.getRuntimeContext().getRuntimeComponent(TemplateService.class);
      dataStorage = operationContext.getRuntimeContext().getRuntimeComponent(DataStorage.class);
      pageService = operationContext.getRuntimeContext().getRuntimeComponent(PageService.class);
      wcmService = operationContext.getRuntimeContext().getRuntimeComponent(WCMService.class);

      List<ExportTask> exportTasks = new ArrayList<ExportTask>();

      List<String> filters = attributes.getValues("filter");

      boolean exportSiteWithSkeleton = true;
      String jcrQuery = null;

      // no-skeleton has the priority over query
      if (!filters.contains("no-skeleton:true") && !filters.contains("no-skeleton:false")) {
        for (String filterValue : filters) {
          if (filterValue.startsWith("query:")) {
            jcrQuery = filterValue.replace("query:", "");
          }
        }
      } else {
        exportSiteWithSkeleton = !filters.contains("no-skeleton:true");
      }

      // workspace
      String workspace = null;
      for (String filterValue : filters) {
        if (filterValue.startsWith("workspace:")) {
          workspace = filterValue.replace("workspace:", "");
        }
      }

      NodeLocation sitesLocation = wcmConfigurationService.getLivePortalsLocation();
      String sitePath = sitesLocation.getPath();
      if (!sitePath.endsWith("/")) {
        sitePath += "/";
      }
      sitePath += siteName;

      if (workspace == null || workspace.isEmpty()) {
        workspace = sitesLocation.getWorkspace();
      }

      metaData.getOptions().put(SiteMetaData.SITE_PATH, sitePath);
      metaData.getOptions().put(SiteMetaData.SITE_WORKSPACE, workspace);
      metaData.getOptions().put(SiteMetaData.SITE_NAME, siteName);

      // "taxonomy" attribute. Defaults to true.
      boolean exportSiteTaxonomy = !filters.contains("taxonomy:false");
      // "no-history" attribute. Defaults to false.
      boolean exportVersionHistory = !filters.contains("no-history:true");

      // Validate Site Structure
      validateSiteStructure(siteName);

      // Site contents
      if (!StringUtils.isEmpty(jcrQuery)) {
        exportTasks.addAll(exportQueryResult(sitesLocation, sitePath, jcrQuery, exportVersionHistory));
      } else if (exportSiteWithSkeleton) {
        exportTasks.addAll(exportSite(sitesLocation, sitePath, exportVersionHistory));
      } else {
        exportTasks.addAll(exportSiteWithoutSkeleton(sitesLocation, sitePath, exportSiteTaxonomy, exportVersionHistory));
      }

      // Metadata
      exportTasks.add(getMetaDataExportTask());

      resultHandler.completed(new ExportResourceModel(exportTasks));
    } catch (Exception e) {
      throw new OperationException(OperationNames.EXPORT_RESOURCE, "Unable to retrieve the list of the contents sites : " + e.getMessage(), e);
    }
  }

  private void validateSiteStructure(String siteName) throws Exception {
    if (siteName.equals(wcmConfigurationService.getSharedPortalName())) {
      return;
    }
    // pages
    Iterator<PageContext> pagesQueryResult = pageService.findPages(0, Integer.MAX_VALUE, SiteType.PORTAL, siteName, null, null).iterator();
    Set<String> contentSet = new HashSet<String>();
    while (pagesQueryResult.hasNext()) {
      PageContext pageContext = (PageContext) pagesQueryResult.next();
      Page page = dataStorage.getPage(pageContext.getKey().format());
      contentSet.addAll(getSCVPaths(page.getChildren()));
      contentSet.addAll(getCLVPaths(page.getChildren()));
    }

    // site layout
    PortalConfig portalConfig = dataStorage.getPortalConfig(siteName);
    if (portalConfig != null) {
      Container portalLayout = portalConfig.getPortalLayout();
      contentSet.addAll(getSCVPaths(portalLayout.getChildren()));
      contentSet.addAll(getCLVPaths(portalLayout.getChildren()));
    }

    if (!contentSet.isEmpty()) {
      log.warn("Site contents export: There are some contents used in pages that don't belong to <<" + siteName + ">> site's JCR structure: " + contentSet);
    }
  }

  @SuppressWarnings(
    { "unchecked", "rawtypes" })
  private List<String> getSCVPaths(ArrayList<ModelObject> children) throws Exception {
    List<String> scvPaths = new ArrayList<String>();
    if (children != null) {
      for (ModelObject modelObject : children) {
        if (modelObject instanceof Application) {
          Portlet portlet = (Portlet) dataStorage.load(((Application) modelObject).getState(), ((Application) modelObject).getType());
          if (portlet == null || portlet.getValue(IDENTIFIER) == null) {
            continue;
          }
          String workspace = portlet.getPreference(WORKSPACE).getValue();
          String nodeIdentifier = portlet.getPreference(IDENTIFIER) == null ? null : portlet.getPreference(IDENTIFIER).getValue();
          if (nodeIdentifier == null || nodeIdentifier.isEmpty()) {
            continue;
          }
          if (workspace.equals(metaData.getOptions().get(SiteMetaData.SITE_WORKSPACE)) && nodeIdentifier.startsWith(metaData.getOptions().get(SiteMetaData.SITE_PATH))) {
            continue;
          }
          String path = nodeIdentifier;
          if (!nodeIdentifier.startsWith("/")) {
            Node node = wcmService.getReferencedContent(SessionProvider.createSystemProvider(), workspace, nodeIdentifier);
            if (node != null) {
              node = Utils.getRealNode(node);
            }
            path = node == null ? null : node.getPath();
          }
          if (path == null || path.isEmpty()) {
            continue;
          }
          scvPaths.add(path);
        } else if (modelObject instanceof Container) {
          scvPaths.addAll(getSCVPaths(((Container) modelObject).getChildren()));
        }
      }
    }
    return scvPaths;
  }

  @SuppressWarnings(
    { "unchecked", "rawtypes" })
  private List<String> getCLVPaths(ArrayList<ModelObject> children) throws Exception {
    List<String> scvPaths = new ArrayList<String>();
    if (children != null) {
      for (ModelObject modelObject : children) {
        if (modelObject instanceof Application) {
          Portlet portlet = (Portlet) dataStorage.load(((Application) modelObject).getState(), ((Application) modelObject).getType());
          if (portlet == null || portlet.getValue(FOLDER_PATH) == null) {
            continue;
          }
          String[] folderPaths = portlet.getPreference(FOLDER_PATH).getValue().split(";");
          for (String folderPath : folderPaths) {
            String[] paths = folderPath.split(":");
            String workspace = paths[1];
            String path = paths[2];
            if (workspace.equals(metaData.getOptions().get(SiteMetaData.SITE_WORKSPACE)) && path.startsWith(metaData.getOptions().get(SiteMetaData.SITE_PATH))) {
              continue;
            }
            scvPaths.add(path);
          }
        } else if (modelObject instanceof Container) {
          scvPaths.addAll(getCLVPaths(((Container) modelObject).getChildren()));
        }
      }
    }
    return scvPaths;
  }

  /**
   * 
   * @param sitesLocation
   * @param siteRootNodePath
   * @param exportVersionHistory
   * @return
   */
  private List<ExportTask> exportSite(NodeLocation sitesLocation, String siteRootNodePath, boolean exportVersionHistory) throws Exception {
    List<ExportTask> exportTasks = new ArrayList<ExportTask>();

    Session session = getSession(sitesLocation.getWorkspace());

    Node siteNode = (Node) session.getItem(siteRootNodePath);
    Node parentNode = siteNode.getParent();

    exportNode(sitesLocation.getWorkspace(), parentNode, null, exportVersionHistory, exportTasks, siteNode);

    return exportTasks;
  }

  /**
   * 
   * @param sitesLocation
   * @param siteRootNodePath
   * @param exportVersionHistory
   * @return
   */
  private List<ExportTask> exportQueryResult(NodeLocation sitesLocation, String siteRootNodePath, String jcrQuery, boolean exportVersionHistory) throws Exception {
    List<ExportTask> exportTasks = new ArrayList<ExportTask>();

    if (!jcrQuery.contains("jcr:path")) {
      String queryPath = "jcr:path = '" + siteRootNodePath + "/%'";
      queryPath.replace("//", "/");
      if (jcrQuery.contains("where")) {
        int startIndex = jcrQuery.indexOf("where");
        int endIndex = startIndex + "where".length();

        String condition = jcrQuery.substring(endIndex);
        condition = queryPath + " AND (" + condition + ")";

        jcrQuery = jcrQuery.substring(0, startIndex) + " where " + condition;
      } else {
        jcrQuery += " where " + queryPath;
      }
    }

    Session session = getSession(sitesLocation.getWorkspace());

    Query query = session.getWorkspace().getQueryManager().createQuery(jcrQuery, Query.SQL);
    NodeIterator nodeIterator = query.execute().getNodes();
    while (nodeIterator.hasNext()) {
      Node node = nodeIterator.nextNode();
      exportNode(sitesLocation.getWorkspace(), node.getParent(), null, exportVersionHistory, exportTasks, node);
    }
    return exportTasks;
  }

  /**
   * 
   * @param sitesLocation
   * @param path
   * @param exportSiteTaxonomy
   * @param exportVersionHistory
   * @return
   * @throws Exception
   * @throws RepositoryException
   */
  private List<ExportTask> exportSiteWithoutSkeleton(NodeLocation sitesLocation, String path, boolean exportSiteTaxonomy, boolean exportVersionHistory) throws Exception, RepositoryException {

    List<ExportTask> exportTasks = new ArrayList<ExportTask>();

    NodeLocation nodeLocation = new NodeLocation("repository", sitesLocation.getWorkspace(), path, null, true);
    Node portalNode = NodeLocation.getNodeByLocation(nodeLocation);

    PortalFolderSchemaHandler portalFolderSchemaHandler = new PortalFolderSchemaHandler();

    // CSS Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getCSSFolder(portalNode), null, exportVersionHistory));

    // JS Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getJSFolder(portalNode), null, exportVersionHistory));

    // Document Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getDocumentStorage(portalNode), null, exportVersionHistory));

    // Images Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getImagesFolder(portalNode), null, exportVersionHistory));

    // Audio Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getAudioFolder(portalNode), null, exportVersionHistory));

    // Video Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getVideoFolder(portalNode), null, exportVersionHistory));

    // Multimedia Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getMultimediaFolder(portalNode), Arrays.asList("images", "audio", "videos"), exportVersionHistory));

    // Link Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getLinkFolder(portalNode), null, exportVersionHistory));

    // WebContent Folder
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), portalFolderSchemaHandler.getWebContentStorage(portalNode), Arrays.asList("site artifacts"), exportVersionHistory));

    // Site Artifacts Folder
    Node webContentNode = portalFolderSchemaHandler.getWebContentStorage(portalNode);
    exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), webContentNode.getNode("site artifacts"), null, exportVersionHistory));

    if (exportSiteTaxonomy) {
      // Categories Folder
      Node categoriesNode = portalNode.getNode("categories");
      exportTasks.addAll(exportSubNodes(sitesLocation.getWorkspace(), categoriesNode, null, exportVersionHistory));
    }
    return exportTasks;
  }

  /**
   * Export all sub-nodes of the given node
   * 
   * @param repositoryService
   * @param workspace
   * @param parentNode
   * @param excludedNodes
   * @return
   * @throws RepositoryException
   */
  protected List<ExportTask> exportSubNodes(String workspace, Node parentNode, List<String> excludedNodes, boolean exportVersionHistory) throws Exception {
    List<ExportTask> subNodesExportTask = new ArrayList<ExportTask>();
    NodeIterator childrenNodes = parentNode.getNodes();
    while (childrenNodes.hasNext()) {
      Node childNode = (Node) childrenNodes.next();
      exportNode(workspace, parentNode, excludedNodes, exportVersionHistory, subNodesExportTask, childNode);
    }
    return subNodesExportTask;
  }

  private void exportNode(String workspace, Node parentNode, List<String> excludedNodes, boolean exportVersionHistory, List<ExportTask> subNodesExportTask, Node childNode) throws Exception {
    if (excludedNodes == null || !excludedNodes.contains(childNode.getName())) {
      boolean recursive = isRecursiveExport(childNode);
      SiteContentsExportTask siteContentExportTask = new SiteContentsExportTask(repositoryService, workspace, metaData.getOptions().get(SiteMetaData.SITE_NAME), childNode.getPath(), recursive);
      subNodesExportTask.add(siteContentExportTask);
      if (exportVersionHistory) {
        SiteContentsVersionHistoryExportTask versionHistoryExportTask = new SiteContentsVersionHistoryExportTask(repositoryService, workspace, metaData.getOptions().get(SiteMetaData.SITE_NAME),
            childNode.getPath(), recursive);
        subNodesExportTask.add(versionHistoryExportTask);
      }
      metaData.getExportedFiles().put(siteContentExportTask.getEntry(), parentNode.getPath());
      if (!recursive) {
        NodeIterator nodeIterator = childNode.getNodes();
        while (nodeIterator.hasNext()) {
          Node node = nodeIterator.nextNode();
          exportNode(workspace, childNode, excludedNodes, exportVersionHistory, subNodesExportTask, node);
        }
      }
    }
  }

  private boolean isRecursiveExport(Node node) throws Exception {

    // FIXME: eXo ECMS bug, items with exo:actionnable don't define manatory
    // field exo:actions. Still use this workaround. EXOCONSULTING-219
    if (node.isNodeType("exo:actionable") && !node.hasProperty("exo:actions")) {
      node.setProperty("exo:actions", "");
      node.save();
      node.getSession().refresh(true);
    }
    // END workaround

    NodeType nodeType = node.getPrimaryNodeType();
    NodeType[] nodeTypes = node.getMixinNodeTypes();
    boolean recursive = isRecursiveNT(nodeType);
    if (nodeTypes != null && nodeTypes.length > 0) {
      int i = 0;
      while (!recursive && i < nodeTypes.length) {
        recursive = isRecursiveNT(nodeTypes[i]);
        i++;
      }
    }
    return recursive;
  }

  private boolean isRecursiveNT(NodeType nodeType) throws Exception {
    if (!isNTRecursiveMap.containsKey(nodeType.getName())) {
      boolean hasMandatoryChild = false;
      NodeDefinition[] nodeDefinitions = nodeType.getChildNodeDefinitions();
      if (nodeDefinitions != null) {
        int i = 0;
        while (!hasMandatoryChild && i < nodeDefinitions.length) {
          hasMandatoryChild = nodeDefinitions[i].isMandatory();
          i++;
        }
      }
      boolean noRecursive = !hasMandatoryChild && !templateService.isManagedNodeType(nodeType.getName());
      isNTRecursiveMap.put(nodeType.getName(), !noRecursive);
    }
    return isNTRecursiveMap.get(nodeType.getName());
  }

  private SiteMetaDataExportTask getMetaDataExportTask() {
    return new SiteMetaDataExportTask(metaData);
  }

  private Session getSession(String workspace) throws RepositoryException, LoginException, NoSuchWorkspaceException {
    SessionProvider provider = SessionProvider.createSystemProvider();
    ManageableRepository repository = repositoryService.getCurrentRepository();
    Session session = provider.getSession(workspace, repository);
    return session;
  }

}
