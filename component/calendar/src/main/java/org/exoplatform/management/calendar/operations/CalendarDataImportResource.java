package org.exoplatform.management.calendar.operations;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.chromattic.common.collection.Collections;
import org.exoplatform.calendar.service.Calendar;
import org.exoplatform.calendar.service.CalendarEvent;
import org.exoplatform.calendar.service.CalendarService;
import org.exoplatform.calendar.service.impl.CalendarServiceImpl;
import org.exoplatform.calendar.service.impl.JCRDataStorage;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.management.calendar.CalendarExtension;
import org.exoplatform.management.common.AbstractOperationHandler;
import org.exoplatform.management.common.MockHttpServletRequest;
import org.exoplatform.management.common.MockHttpServletResponse;
import org.exoplatform.management.common.SpaceMetaData;
import org.exoplatform.portal.application.PortalRequestContext;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.portal.config.UserPortalConfigService;
import org.exoplatform.portal.mop.SiteKey;
import org.exoplatform.portal.mop.SiteType;
import org.exoplatform.portal.mop.navigation.NavigationContext;
import org.exoplatform.portal.mop.navigation.NavigationService;
import org.exoplatform.portal.mop.navigation.NodeContext;
import org.exoplatform.portal.mop.navigation.NodeModel;
import org.exoplatform.portal.mop.navigation.Scope;
import org.exoplatform.portal.mop.user.UserNavigation;
import org.exoplatform.portal.mop.user.UserNode;
import org.exoplatform.portal.mop.user.UserPortal;
import org.exoplatform.portal.url.PortalURLContext;
import org.exoplatform.portal.webui.util.Util;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.space.SpaceUtils;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.social.core.storage.api.ActivityStorage;
import org.exoplatform.social.core.storage.api.IdentityStorage;
import org.exoplatform.web.ControllerContext;
import org.exoplatform.web.application.RequestContext;
import org.exoplatform.web.url.PortalURL;
import org.exoplatform.web.url.ResourceType;
import org.exoplatform.web.url.URLFactory;
import org.exoplatform.web.url.navigation.NavigationResource;
import org.exoplatform.web.url.navigation.NodeURL;
import org.exoplatform.webui.application.WebuiApplication;
import org.exoplatform.webui.application.WebuiRequestContext;
import org.gatein.common.logging.Logger;
import org.gatein.common.logging.LoggerFactory;
import org.gatein.management.api.exceptions.OperationException;
import org.gatein.management.api.operation.OperationAttachment;
import org.gatein.management.api.operation.OperationAttributes;
import org.gatein.management.api.operation.OperationContext;
import org.gatein.management.api.operation.OperationNames;
import org.gatein.management.api.operation.ResultHandler;
import org.gatein.management.api.operation.model.NoResultModel;

import com.thoughtworks.xstream.XStream;

/**
 * @author <a href="mailto:bkhanfir@exoplatform.com">Boubaker Khanfir</a>
 * @version $Revision$
 */
public class CalendarDataImportResource extends AbstractOperationHandler {

  private static final String INTRANET_SITE_NAME = "intranet";

  final private static Logger log = LoggerFactory.getLogger(CalendarDataImportResource.class);

  final private static int BUFFER = 2048000;

  public static final String EVENT_ID_KEY = "EventID";

  private static final String CALENDAR_SPACE_NAGVIGATION = "calendar";

  private static final String CALENDAR_PORTLET_NAME = "CalendarPortlet";

  public static final String INVITATION_DETAIL = "/invitation/detail/";

  public static final String EVENT_LINK_KEY = "EventLink";

  private CalendarService calendarService;

  private JCRDataStorage calendarStorage;
  private UserPortalConfigService portalConfigService;

  private boolean groupCalendar;
  private boolean spaceCalendar;
  private String type;

  public CalendarDataImportResource(boolean groupCalendar, boolean spaceCalendar) {
    this.groupCalendar = groupCalendar;
    this.spaceCalendar = spaceCalendar;
    type = groupCalendar ? spaceCalendar ? CalendarExtension.SPACE_CALENDAR_TYPE : CalendarExtension.GROUP_CALENDAR_TYPE : CalendarExtension.PERSONAL_CALENDAR_TYPE;
  }

  @Override
  public void execute(OperationContext operationContext, ResultHandler resultHandler) throws OperationException {
    calendarService = operationContext.getRuntimeContext().getRuntimeComponent(CalendarService.class);
    calendarStorage = ((CalendarServiceImpl) calendarService).getDataStorage();
    spaceService = operationContext.getRuntimeContext().getRuntimeComponent(SpaceService.class);
    activityManager = operationContext.getRuntimeContext().getRuntimeComponent(ActivityManager.class);
    activityStorage = operationContext.getRuntimeContext().getRuntimeComponent(ActivityStorage.class);
    identityStorage = operationContext.getRuntimeContext().getRuntimeComponent(IdentityStorage.class);
    userACL = operationContext.getRuntimeContext().getRuntimeComponent(UserACL.class);
    portalConfigService = operationContext.getRuntimeContext().getRuntimeComponent(UserPortalConfigService.class);

    OperationAttributes attributes = operationContext.getAttributes();
    List<String> filters = attributes.getValues("filter");

    // "replace-existing" attribute. Defaults to false.
    boolean replaceExisting = filters.contains("replace-existing:true");

    // "create-space" attribute. Defaults to false.
    boolean createSpace = filters.contains("create-space:true");

    OperationAttachment attachment = operationContext.getAttachment(false);
    if (attachment == null) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "No attachment available for calendar import.");
    }

    InputStream attachmentInputStream = attachment.getStream();
    if (attachmentInputStream == null) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "No data stream available for calendar import.");
    }

    String tempFolderPath = null;
    Set<String> contentsByOwner = new HashSet<String>();

    RequestContext originalRequestContext = null;
    try {
      // extract data from zip
      tempFolderPath = extractDataFromZip(attachmentInputStream, contentsByOwner);

      // FIXME: INTEG-333. Add this to not have a null pointer exception while
      // importing
      if (!contentsByOwner.isEmpty()) {
        originalRequestContext = fixPortalRequest();
      }

      List<File> activitiesFiles = new ArrayList<File>();
      for (String tempFilePath : contentsByOwner) {
        if (tempFilePath.endsWith(CalendarActivitiesExportTask.FILENAME)) {
          activitiesFiles.add(new File(tempFilePath));
        } else {
          importCalendar(tempFolderPath, tempFilePath, replaceExisting, createSpace);
        }
      }
      for (File activitiesFile : activitiesFiles) {
        String tempFilePath = activitiesFile.getAbsolutePath();
        String spaceGroupName = tempFilePath.contains("_space_calendar") ? tempFilePath.substring(
            tempFilePath.indexOf(CalendarExportTask.CALENDAR_SEPARATOR) + CalendarExportTask.CALENDAR_SEPARATOR.length(), tempFilePath.indexOf("_space_calendar")) : null;
        String spaceGroupId = SpaceUtils.SPACE_GROUP + "/" + spaceGroupName;
        Space space = spaceService.getSpaceByGroupId(spaceGroupId);
        createActivities(activitiesFile, space.getPrettyName());
      }
    } catch (Exception e) {
      throw new OperationException(OperationNames.IMPORT_RESOURCE, "Unable to import calendar contents", e);
    } finally {
      if (originalRequestContext != null) {
        WebuiRequestContext.setCurrentInstance(originalRequestContext);
      }
      if (attachmentInputStream != null) {
        try {
          attachmentInputStream.close();
        } catch (IOException e) {
          // Nothing to do
        }
      }
      if (tempFolderPath != null) {
        try {
          FileUtils.deleteDirectory(new File(tempFolderPath));
        } catch (IOException e) {
          log.warn("Unable to delete temp folder: " + tempFolderPath + ". Not blocker.", e);
        }
      }

    }
    resultHandler.completed(NoResultModel.INSTANCE);
  }

  private RequestContext fixPortalRequest() {
    RequestContext originalRequestContext;
    originalRequestContext = WebuiRequestContext.getCurrentInstance();
    if (originalRequestContext == null) {
      final ControllerContext controllerContext = new ControllerContext(null, null, new MockHttpServletRequest(), new MockHttpServletResponse(), null);
      PortalRequestContext portalRequestContext = new PortalRequestContext((WebuiApplication) null, controllerContext, groupCalendar ? SiteType.GROUP.getName() : SiteType.PORTAL.getName(),
          portalConfigService.getDefaultPortal(), "/portal/" + portalConfigService.getDefaultPortal() + "/calendar", (Locale) null) {
        @Override
        public <R, U extends PortalURL<R, U>> U newURL(ResourceType<R, U> resourceType, URLFactory urlFactory) {
          if (resourceType.equals(NodeURL.TYPE)) {
            @SuppressWarnings("unchecked")
            U u = (U) new NodeURL(new PortalURLContext(controllerContext, null) {
              public <S extends Object, V extends org.exoplatform.web.url.PortalURL<S, V>> String render(V url) {
                return "";
              };
            });
            return u;
          }
          return super.newURL(resourceType, urlFactory);
        }
      };
      WebuiRequestContext.setCurrentInstance(portalRequestContext);
    }
    return originalRequestContext;
  }

  /**
   * Extract data from zip
   * 
   * @param attachment
   * @return
   */
  public String extractDataFromZip(InputStream attachmentInputStream, Set<String> contentsByCalendar) throws Exception {
    File tmpZipFile = null;
    String targetFolderPath = null;
    try {
      tmpZipFile = copyAttachementToLocalFolder(attachmentInputStream);

      // Get path of folder where to unzip files
      targetFolderPath = tmpZipFile.getAbsolutePath().replaceAll("\\.zip$", "") + "/";

      // Organize File paths by id and extract files from zip to a temp
      // folder
      extractFiles(tmpZipFile, targetFolderPath, contentsByCalendar);
    } finally {
      if (tmpZipFile != null) {
        try {
          FileUtils.forceDelete(tmpZipFile);
        } catch (Exception e) {
          log.warn("Unable to delete temp file: " + tmpZipFile.getAbsolutePath() + ". Not blocker.");
          tmpZipFile.deleteOnExit();
        }
      }
    }
    return targetFolderPath;
  }

  private File copyAttachementToLocalFolder(InputStream attachmentInputStream) throws IOException, FileNotFoundException {
    NonCloseableZipInputStream zis = null;
    File tmpZipFile = null;
    try {
      // Copy attachement to local File
      tmpZipFile = File.createTempFile("staging-calendar", ".zip");
      tmpZipFile.deleteOnExit();
      FileOutputStream tmpFileOutputStream = new FileOutputStream(tmpZipFile);
      IOUtils.copy(attachmentInputStream, tmpFileOutputStream);
      tmpFileOutputStream.close();
      attachmentInputStream.close();
    } finally {
      if (zis != null) {
        try {
          zis.reallyClose();
        } catch (IOException e) {
          log.warn("Can't close inputStream of attachement.");
        }
      }
    }
    return tmpZipFile;
  }

  private boolean createSpaceIfNotExists(String tempFolderPath, String spacePrettyName, String groupId, boolean createSpace) throws IOException {
    Space space = spaceService.getSpaceByGroupId(groupId);
    if (space == null) {
      space = spaceService.getSpaceByPrettyName(spacePrettyName);
    }
    if (space == null && createSpace) {
      FileInputStream spaceMetadataFile = new FileInputStream(tempFolderPath + "/" + SpaceMetadataExportTask.getEntryPath(spacePrettyName));
      try {
        // Unmarshall metadata xml file
        XStream xstream = new XStream();
        xstream.alias("metadata", SpaceMetaData.class);
        SpaceMetaData spaceMetaData = (SpaceMetaData) xstream.fromXML(spaceMetadataFile);

        log.info("Automatically create new space: '" + spaceMetaData.getPrettyName() + "'.");
        space = new Space();
        space.setPrettyName(spaceMetaData.getPrettyName());
        space.setDisplayName(spaceMetaData.getDisplayName());
        space.setGroupId(groupId);
        space.setTag(spaceMetaData.getTag());
        space.setApp(spaceMetaData.getApp());
        space.setEditor(spaceMetaData.getEditor() != null ? spaceMetaData.getEditor() : spaceMetaData.getManagers().length > 0 ? spaceMetaData.getManagers()[0] : userACL.getSuperUser());
        space.setManagers(spaceMetaData.getManagers());
        space.setInvitedUsers(spaceMetaData.getInvitedUsers());
        space.setRegistration(spaceMetaData.getRegistration());
        space.setDescription(spaceMetaData.getDescription());
        space.setType(spaceMetaData.getType());
        space.setVisibility(spaceMetaData.getVisibility());
        space.setPriority(spaceMetaData.getPriority());
        space.setUrl(spaceMetaData.getUrl());
        spaceService.createSpace(space, space.getEditor());
        return true;
      } finally {
        if (spaceMetadataFile != null) {
          try {
            spaceMetadataFile.close();
          } catch (Exception e) {
            log.warn(e);
          }
        }
      }
    }
    return (space != null);
  }

  private void importCalendar(String tempFolderPath, String tempFilePath, boolean replaceExisting, boolean createSpace) throws Exception {
    // Unmarshall calendar data file
    XStream xStream = new XStream();
    xStream.alias("Calendar", Calendar.class);
    xStream.alias("Event", CalendarEvent.class);

    @SuppressWarnings("unchecked")
    List<Object> objects = (List<Object>) xStream.fromXML(FileUtils.readFileToString(new File(tempFilePath), "UTF-8"));

    Calendar calendar = (Calendar) objects.get(0);
    if (groupCalendar && (calendar.getCalendarOwner() == null || !calendar.getCalendarOwner().startsWith(SpaceUtils.SPACE_GROUP))) {
      String[] groups = calendar.getGroups();
      if (groups != null) {
        for (String groupId : groups) {
          if (groupId != null && groupId.startsWith(SpaceUtils.SPACE_GROUP)) {
            calendar.setCalendarOwner(groups[0]);
            break;
          }
        }
      }
    }

    Calendar toReplaceCalendar = calendarService.getCalendarById(calendar.getId());
    if (toReplaceCalendar != null) {
      if (replaceExisting) {
        log.info("Overwrite existing calendar: " + toReplaceCalendar.getName());
        if (groupCalendar) {
          // FIXME event activities aren't deleted
          List<CalendarEvent> events = calendarService.getGroupEventByCalendar(Collections.list(calendar.getId()));
          deleteCalendarActivities(events);

          // Delete Calendar
          calendarService.removePublicCalendar(calendar.getId());

          // Save new Calendar
          calendarStorage.savePublicCalendar(calendar, true, null);
        } else {
          // FIXME event activities aren't deleted
          List<CalendarEvent> events = calendarService.getUserEventByCalendar(calendar.getCalendarOwner(), Collections.list(calendar.getId()));
          deleteCalendarActivities(events);

          // Delete Calendar
          calendarService.removeUserCalendar(calendar.getCalendarOwner(), calendar.getId());

          // Save new Calendar
          calendarStorage.saveUserCalendar(calendar.getCalendarOwner(), calendar, true);
        }
      } else {
        log.info("Ignore existing calendar: " + toReplaceCalendar.getName());
      }
    } else {
      log.info("Create calendar: " + calendar.getName());
      if (groupCalendar) {
        if (spaceCalendar) {
          String groupId = calendar.getCalendarOwner();
          String spacePrettyName = groupId.replace(SpaceUtils.SPACE_GROUP + "/", "");

          boolean spaceCreatedOrAlreadyExists = createSpaceIfNotExists(tempFolderPath, spacePrettyName, groupId, createSpace);
          if (!spaceCreatedOrAlreadyExists) {
            log.warn("Import of Calendar of space '" + spacePrettyName + "' is ignored. Turn on 'create-space:true' option if you want to automatically create the space.");
            return;
          }
          calendarService.removePublicCalendar(calendar.getId());
        }
        calendarStorage.savePublicCalendar(calendar, true, null);
      } else {
        calendarStorage.saveUserCalendar(calendar.getCalendarOwner(), calendar, true);
      }
    }
    @SuppressWarnings("unchecked")
    List<CalendarEvent> events = (List<CalendarEvent>) objects.get(1);
    for (CalendarEvent event : events) {
      log.info("Create calendar event: " + calendar.getName() + "/" + event.getSummary());
      if (groupCalendar) {
        calendarStorage.savePublicEvent(calendar.getId(), event, true);
      } else {
        calendarStorage.saveUserEvent(calendar.getCalendarOwner(), calendar.getId(), event, true);
      }
    }
    deleteCalendarActivities(events);
  }

  private void deleteCalendarActivities(List<CalendarEvent> events) {
    for (CalendarEvent event : events) {
      if (event != null && event.getActivityId() != null) {
        deleteActivity(event.getActivityId());
      }
    }
  }

  private void updateCalendarActivityURL(CalendarEvent event, String groupId) {
    ExoSocialActivity activity = activityManager.getActivity(event.getActivityId());
    if (activity != null) {
      Map<String, String> templateParams = activity.getTemplateParams();
      if (templateParams.containsKey(EVENT_LINK_KEY)) {
        templateParams.put(EVENT_LINK_KEY, getLink(event, activity, groupId));
        activityManager.updateActivity(activity);
      }
    }
  }

  private void extractFiles(File tmpZipFile, String targetFolderPath, Set<String> contentsByOwner) throws Exception {
    // Open an input stream on local zip file
    NonCloseableZipInputStream zis = new NonCloseableZipInputStream(new FileInputStream(tmpZipFile));

    try {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        String filePath = entry.getName();
        // Skip entries not managed by this extension
        if (filePath.equals("") || !filePath.startsWith("calendar/" + type + "/")) {
          continue;
        }

        // Skip directories
        if (entry.isDirectory()) {
          // Create directory in unzipped folder location
          createFile(new File(targetFolderPath + filePath), true);
          continue;
        }

        // Skip non managed
        if (!filePath.endsWith(".xml") && !filePath.endsWith(SpaceMetadataExportTask.FILENAME) && !filePath.contains(CalendarExportTask.CALENDAR_SEPARATOR)) {
          log.warn("Uknown file format found at location: '" + filePath + "'. Ignore it.");
          continue;
        }

        log.info("Receiving content " + filePath);

        // Put XML Export file in temp folder
        copyToDisk(zis, targetFolderPath + filePath);

        // Skip metadata file
        if (filePath.endsWith(SpaceMetadataExportTask.FILENAME)) {
          continue;
        }

        contentsByOwner.add(targetFolderPath + filePath);
      }
    } finally {
      if (zis != null) {
        zis.reallyClose();
      }
    }
  }

  // Bug in SUN's JDK XMLStreamReader implementation closes the underlying
  // stream when
  // it finishes reading an XML document. This is no good when we are using
  // a
  // ZipInputStream.
  // See http://bugs.sun.com/view_bug.do?bug_id=6539065 for more
  // information.
  public static class NonCloseableZipInputStream extends ZipInputStream {
    public NonCloseableZipInputStream(InputStream inputStream) {
      super(inputStream);
    }

    @Override
    public void close() throws IOException {}

    private void reallyClose() throws IOException {
      super.close();
    }
  }

  private static void copyToDisk(InputStream input, String output) throws Exception {
    byte data[] = new byte[BUFFER];
    BufferedOutputStream dest = null;
    try {
      FileOutputStream fileOuput = new FileOutputStream(createFile(new File(output), false));
      dest = new BufferedOutputStream(fileOuput, BUFFER);
      int count = 0;
      while ((count = input.read(data, 0, BUFFER)) != -1)
        dest.write(data, 0, count);
    } finally {
      if (dest != null) {
        dest.close();
      }
    }
  }

  private static File createFile(File file, boolean folder) throws Exception {
    if (file.getParentFile() != null)
      createFile(file.getParentFile(), true);
    if (file.exists())
      return file;
    if (file.isDirectory() || folder)
      file.mkdir();
    else
      file.createNewFile();
    return file;
  }

  private String getLink(CalendarEvent event, ExoSocialActivity activity, String spaceGroupId) {
    if (spaceGroupId == null) {
      PortalRequestContext prc = Util.getPortalRequestContext();
      UserPortal userPortal = prc.getUserPortal();
      UserNavigation userNav = userPortal.getNavigation(SiteKey.portal(INTRANET_SITE_NAME));
      UserNode userNode = userPortal.getNode(userNav, Scope.ALL, null, null);
      UserNode calendarNode = userNode.getChild(CALENDAR_SPACE_NAGVIGATION);
      if (calendarNode != null) {
        String calendarURI = getNodeURL(calendarNode);
        return makeEventLink(event, calendarURI);
      }
      return StringUtils.EMPTY;
    } else {
      String spaceLink = getSpaceHomeURL(spaceGroupId);
      if (spaceLink == null || spaceLink.isEmpty()) {
        spaceLink = activity.getStreamUrl();
      }
      if (getAnswerPortletInSpace(spaceGroupId).length() == 0) {
        return StringUtils.EMPTY;
      }
      return makeEventLink(event, spaceLink + "/" + getAnswerPortletInSpace(spaceGroupId));
    }
  }

  private String makeEventLink(CalendarEvent event, String baseURI) {
    StringBuffer sb = new StringBuffer("");
    sb.append(baseURI).append(INVITATION_DETAIL).append(ConversationState.getCurrent().getIdentity().getUserId()).append("/").append(event.getId()).append("/").append(event.getCalType());
    return sb.toString();
  }

  private String getSpaceHomeURL(String spaceGroupId) {
    if (spaceGroupId == null || "".equals(spaceGroupId))
      return null;
    String permanentSpaceName = spaceGroupId.split("/")[2];
    Space space = spaceService.getSpaceByGroupId(spaceGroupId);

    NodeURL nodeURL = RequestContext.getCurrentInstance().createURL(NodeURL.TYPE);
    NavigationResource resource = new NavigationResource(SiteType.GROUP, SpaceUtils.SPACE_GROUP + "/" + permanentSpaceName, space.getPrettyName());

    return nodeURL.setResource(resource).toString();
  }

  private String getAnswerPortletInSpace(String spaceGroupId) {
    ExoContainer container = ExoContainerContext.getCurrentContainer();
    NavigationService navService = (NavigationService) container.getComponentInstance(NavigationService.class);
    NavigationContext nav = navService.loadNavigation(SiteKey.group(spaceGroupId));
    NodeContext<NodeContext<?>> parentNodeCtx = navService.loadNode(NodeModel.SELF_MODEL, nav, Scope.ALL, null);

    if (parentNodeCtx.getSize() >= 1) {
      NodeContext<?> nodeCtx = parentNodeCtx.get(0);
      @SuppressWarnings("unchecked")
      Collection<NodeContext<?>> children = (Collection<NodeContext<?>>) nodeCtx.getNodes();
      Iterator<NodeContext<?>> it = children.iterator();

      NodeContext<?> child = null;
      while (it.hasNext()) {
        child = it.next();
        if (CALENDAR_SPACE_NAGVIGATION.equals(child.getName()) || child.getName().indexOf(CALENDAR_PORTLET_NAME) >= 0) {
          return child.getName();
        }
      }
    }
    return StringUtils.EMPTY;
  }

  private String getNodeURL(UserNode node) {
    RequestContext ctx = RequestContext.getCurrentInstance();
    NodeURL nodeURL = ctx.createURL(NodeURL.TYPE);
    return nodeURL.setNode(node).toString();
  }

  @SuppressWarnings("unchecked")
  private void createActivities(File activitiesFile, String spacePrettyName) {
    log.info("Importing Calendar activities");
    List<ExoSocialActivity> activities = null;
    FileInputStream inputStream = null;
    try {
      inputStream = new FileInputStream(activitiesFile);

      // Unmarshall metadata xml file
      XStream xstream = new XStream();

      activities = (List<ExoSocialActivity>) xstream.fromXML(inputStream);
    } catch (FileNotFoundException e) {
      log.error("Error while getting activities file", e);
    } finally {
      if (inputStream != null) {
        try {
          inputStream.close();
        } catch (IOException e) {
          log.warn("Cannot close input stream: " + activitiesFile.getAbsolutePath() + ". Ignore non blocking operation.");
        }
      }
    }

    List<ExoSocialActivity> activitiesList = sanitizeContent(activities);

    Calendar calendar = null;
    ExoSocialActivity eventActivity = null;
    for (ExoSocialActivity exoSocialActivity : activitiesList) {
      try {
        exoSocialActivity.setId(null);
        if (exoSocialActivity.isComment()) {
          if (eventActivity == null) {
            log.warn("An Calendar activity comment was found, but with no calendar: " + exoSocialActivity.getTitle());
          } else {
            saveComment(eventActivity, exoSocialActivity);
          }
        } else {
          eventActivity = null;
          String eventId = exoSocialActivity.getTemplateParams().get(EVENT_ID_KEY);
          if (eventId == null) {
            log.warn("An unkown Calendar activity was found: " + exoSocialActivity.getTitle());
            continue;
          }
          CalendarEvent event = calendarService.getEventById(eventId);
          if (event == null) {
            log.warn("Calendar event not found. Cannot import activity '" + exoSocialActivity.getTitle() + "'.");
            continue;
          }
          saveActivity(exoSocialActivity, spacePrettyName);
          if (exoSocialActivity.getId() == null) {
            log.warn("Activity '" + exoSocialActivity.getTitle() + "' is not imported, id is null");
            continue;
          }
          event.setActivityId(exoSocialActivity.getId());
          calendar = saveEvent(calendar, event, exoSocialActivity);
          eventActivity = exoSocialActivity;
        }
      } catch (Exception e) {
        log.warn("Error while importing activity: " + exoSocialActivity.getTitle(), e);
      }
    }
  }

  private Calendar saveEvent(Calendar calendar, CalendarEvent event, ExoSocialActivity exoSocialActivity) throws Exception {
    if (calendar == null) {
      calendar = calendarService.getCalendarById(event.getCalendarId());
    }

    if (calendar.getCalendarOwner().startsWith("/")) {
      calendarStorage.savePublicEvent(event.getCalendarId(), event, false);
      // FIXME the URL of Stream Activity will use staging URL, modify it
      updateCalendarActivityURL(event, spaceCalendar ? calendar.getCalendarOwner() : null);
    } else {
      calendarStorage.saveUserEvent(userACL.getSuperUser(), event.getCalendarId(), event, false);
      // FIXME the URL of Stream Activity will use staging URL, modify it
      updateCalendarActivityURL(event, null);
    }

    return calendar;
  }
}