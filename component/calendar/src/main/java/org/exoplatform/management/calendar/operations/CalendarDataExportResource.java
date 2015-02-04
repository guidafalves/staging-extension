/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.management.calendar.operations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.chromattic.common.collection.Collections;
import org.exoplatform.calendar.service.Calendar;
import org.exoplatform.calendar.service.CalendarEvent;
import org.exoplatform.calendar.service.CalendarService;
import org.exoplatform.calendar.service.GroupCalendarData;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.management.calendar.CalendarExtension;
import org.exoplatform.management.common.AbstractExportOperationHandler;
import org.exoplatform.management.common.activities.ActivitiesExportTask;
import org.exoplatform.management.common.activities.SpaceMetadataExportTask;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.organization.Group;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.social.common.RealtimeListAccess;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.provider.SpaceIdentityProvider;
import org.exoplatform.social.core.manager.ActivityManager;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.SpaceUtils;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.gatein.management.api.exceptions.OperationException;
import org.gatein.management.api.exceptions.ResourceNotFoundException;
import org.gatein.management.api.operation.OperationContext;
import org.gatein.management.api.operation.OperationNames;
import org.gatein.management.api.operation.ResultHandler;
import org.gatein.management.api.operation.model.ExportResourceModel;
import org.gatein.management.api.operation.model.ExportTask;

/**
 * @author <a href="mailto:bkhanfir@exoplatform.com">Boubaker Khanfir</a>
 * @version $Revision$
 */
public class CalendarDataExportResource extends AbstractExportOperationHandler {

  private boolean groupCalendar;
  private boolean spaceCalendar;
  private String type;

  private UserACL userACL;
  private IdentityManager identityManager;
  private OrganizationService organizationService;

  public CalendarDataExportResource(boolean groupCalendar, boolean spaceCalendar) {
    this.groupCalendar = groupCalendar;
    this.spaceCalendar = spaceCalendar;
    type = groupCalendar ? spaceCalendar ? CalendarExtension.SPACE_CALENDAR_TYPE : CalendarExtension.GROUP_CALENDAR_TYPE : CalendarExtension.PERSONAL_CALENDAR_TYPE;
  }

  @Override
  public void execute(OperationContext operationContext, ResultHandler resultHandler) throws ResourceNotFoundException, OperationException {
    CalendarService calendarService = operationContext.getRuntimeContext().getRuntimeComponent(CalendarService.class);
    spaceService = operationContext.getRuntimeContext().getRuntimeComponent(SpaceService.class);
    userACL = operationContext.getRuntimeContext().getRuntimeComponent(UserACL.class);
    organizationService = operationContext.getRuntimeContext().getRuntimeComponent(OrganizationService.class);
    activityManager = operationContext.getRuntimeContext().getRuntimeComponent(ActivityManager.class);
    identityManager = operationContext.getRuntimeContext().getRuntimeComponent(IdentityManager.class);

    String excludeSpaceMetadataString = operationContext.getAttributes().getValue("exclude-space-metadata");
    boolean exportSpaceMetadata = excludeSpaceMetadataString == null || excludeSpaceMetadataString.trim().equalsIgnoreCase("false");

    List<ExportTask> exportTasks = new ArrayList<ExportTask>();

    if (groupCalendar) {
      String filterText = operationContext.getAttributes().getValue("filter");
      if (spaceCalendar) {
        Space space = spaceService.getSpaceByDisplayName(filterText);
        if (space == null) {
          throw new OperationException(OperationNames.EXPORT_RESOURCE, "Can't find space with display name: " + filterText);
        }
        filterText = space.getGroupId();
      }
      if (filterText == null || filterText.trim().isEmpty()) {
        try {
          @SuppressWarnings("unchecked")
          Collection<Group> groups = organizationService.getGroupHandler().getAllGroups();
          for (Group group : groups) {
            if (spaceCalendar) {
              if (group.getId().startsWith(SpaceUtils.SPACE_GROUP + "/")) {
                exportGroupCalendar(calendarService, userACL, exportTasks, group.getId(), null, exportSpaceMetadata);
              }
            } else {
              if (!group.getId().startsWith(SpaceUtils.SPACE_GROUP + "/")) {
                exportGroupCalendar(calendarService, userACL, exportTasks, group.getId(), null, exportSpaceMetadata);
              }
            }
          }
        } catch (Exception e) {
          throw new OperationException(OperationNames.EXPORT_RESOURCE, "Error while exporting calendars.", e);
        }
      } else {
        // Calendar groupId in case of space or Calendar name in case of simple
        // Group calendar
        exportGroupCalendar(calendarService, userACL, exportTasks, spaceCalendar ? filterText : null, spaceCalendar ? null : filterText, exportSpaceMetadata);
      }
    } else {
      String username = operationContext.getAttributes().getValue("filter");
      if (username == null || username.trim().isEmpty()) {
        OrganizationService organizationService = operationContext.getRuntimeContext().getRuntimeComponent(OrganizationService.class);
        try {
          ListAccess<User> users = organizationService.getUserHandler().findAllUsers();
          int size = users.getSize(), i = 0;
          while (i < size) {
            int length = i + 10 < size ? 10 : size - i;
            User[] usersArr = users.load(0, length);
            for (User user : usersArr) {
              exportUserCalendar(calendarService, userACL, exportTasks, user.getUserName());
            }
          }
        } catch (Exception e) {
          throw new OperationException(OperationNames.EXPORT_RESOURCE, "Error while exporting calendars.", e);
        }
      } else {
        exportUserCalendar(calendarService, userACL, exportTasks, username);
      }
    }
    resultHandler.completed(new ExportResourceModel(exportTasks));
  }

  private void exportGroupCalendar(CalendarService calendarService, UserACL userACL, List<ExportTask> exportTasks, String groupId, String calendarName, boolean exportSpaceMetadata) {
    try {
      List<GroupCalendarData> groupCalendars = calendarService.getGroupCalendars(groupId == null ? getAllGroupIDs() : new String[] { groupId }, true, userACL.getSuperUser());
      List<Calendar> calendars = new ArrayList<Calendar>();

      GROUP_CALENDAR_LOOP: for (GroupCalendarData groupCalendarData : groupCalendars) {
        if (groupCalendarData.getCalendars() != null) {
          if (calendarName != null && !calendarName.isEmpty()) {
            for (Calendar calendar : groupCalendarData.getCalendars()) {
              if (calendar.getName().equals(calendarName)) {
                calendars.add(calendar);
                break GROUP_CALENDAR_LOOP;
              }
            }
          } else {
            calendars.addAll(groupCalendarData.getCalendars());
          }
        }
      }

      Set<String> exportedSpaces = new HashSet<String>();
      for (Calendar calendar : calendars) {
        exportGroupCalendar(calendarService, exportTasks, calendar);
        if (exportSpaceMetadata && spaceCalendar) {
          Space space = spaceService.getSpaceByGroupId(calendar.getCalendarOwner());
          if (space == null) {
            log.error("Can't export space of calendar '" + calendar.getName() + "', can't find space of owner : " + calendar.getCalendarOwner());
          } else {
            exportedSpaces.add(calendar.getCalendarOwner());
            String prefix = "calendar/space/" + CalendarExportTask.CALENDAR_SEPARATOR + calendar.getId() + "/";
            exportTasks.add(new SpaceMetadataExportTask(space, prefix));
          }
        }
      }
    } catch (Exception e) {
      throw new OperationException(OperationNames.EXPORT_RESOURCE, "Error occured while exporting Group Calendar data");
    }
  }

  private String[] getAllGroupIDs() throws Exception {
    @SuppressWarnings("unchecked")
    Collection<Group> groups = organizationService.getGroupHandler().getAllGroups();
    String[] groupIDs = new String[groups.size()];
    int i = 0;
    for (Group group : groups) {
      groupIDs[i++] = group.getId();
    }
    return groupIDs;
  }

  private void exportGroupCalendar(CalendarService calendarService, List<ExportTask> exportTasks, Calendar calendar) throws Exception {
    List<CalendarEvent> events = calendarService.getGroupEventByCalendar(Collections.list(calendar.getId()));
    exportTasks.add(new CalendarExportTask(type, calendar, events));
    exportActivities(exportTasks, events);
  }

  private void exportUserCalendar(CalendarService calendarService, UserACL userACL, List<ExportTask> exportTasks, String username) {
    try {
      List<Calendar> userCalendars = calendarService.getUserCalendars(username, true);
      if (userCalendars.size() > 0) {
        for (Calendar calendar : userCalendars) {
          List<CalendarEvent> events = calendarService.getUserEventByCalendar(username, Collections.list(calendar.getId()));
          exportTasks.add(new CalendarExportTask(type, calendar, events));
          exportActivities(exportTasks, events);
        }
      }
    } catch (Exception e) {
      throw new OperationException(OperationNames.EXPORT_RESOURCE, "Error occured while exporting Group Calendar data");
    }
  }

  private void exportActivities(List<ExportTask> exportTasks, List<CalendarEvent> events) throws Exception {
    if (events == null || events.isEmpty()) {
      return;
    }
    List<ExoSocialActivity> activitiesList = new ArrayList<ExoSocialActivity>();
    String spaceGroupId = SpaceUtils.SPACE_GROUP + "/" + events.get(0).getCalendarId().replace("_space_calendar", "");
    Space space = spaceService.getSpaceByGroupId(spaceGroupId);
    if (space == null) {
      log.warn("Can't find space with group id: " + spaceGroupId);
      return;
    }
    Identity identity = identityManager.getOrCreateIdentity(SpaceIdentityProvider.NAME, space.getPrettyName(), false);
    RealtimeListAccess<ExoSocialActivity> listAccess = activityManager.getActivitiesOfSpaceWithListAccess(identity);
    listAccess.getNumberOfUpgrade();
    if (listAccess.getSize() == 0) {
      return;
    }
    ExoSocialActivity[] activities = listAccess.load(0, listAccess.getSize());
    for (ExoSocialActivity activity : activities) {
      if (activity.getType() != null && activity.getType().equals("cs-calendar:spaces")) {
        String eventId = activity.getTemplateParams().get("EventID");
        CalendarEvent event = getEventFromList(eventId, events);
        if (event != null) {
          addActivityWithComments(activitiesList, activity);
        } else {
          log.warn("Can't find event of calendar activity: " + activity.getTitle());
        }
      }
    }
    if (!activitiesList.isEmpty()) {
      String prefix = "calendar/" + type + "/" + CalendarExportTask.CALENDAR_SEPARATOR + events.get(0).getCalendarId() + "/";
      exportTasks.add(new ActivitiesExportTask(identityManager, activitiesList, prefix));
    }
  }

  private CalendarEvent getEventFromList(String eventId, List<CalendarEvent> events) {
    CalendarEvent event = null;
    for (CalendarEvent tmpEvent : events) {
      if (tmpEvent.getId().equals(eventId)) {
        event = tmpEvent;
        break;
      }
    }
    return event;
  }
}
