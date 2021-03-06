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
package org.exoplatform.management.backup.service.idm;

import org.exoplatform.commons.utils.PrivilegedFileHelper;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.management.backup.operations.BackupExportResource;
import org.exoplatform.management.backup.service.BackupInProgressException;
import org.exoplatform.services.database.HibernateService;
import org.exoplatform.services.database.utils.JDBCUtils;
import org.exoplatform.services.jcr.core.security.JCRRuntimePermissions;
import org.exoplatform.services.jcr.impl.Constants;
import org.exoplatform.services.jcr.impl.backup.BackupException;
import org.exoplatform.services.jcr.impl.dataflow.serialization.ZipObjectWriter;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.hibernate.internal.SessionFactoryImpl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;

/**
 * The Class IDMBackup.
 *
 * @author <a href="mailto:boubaker.khanfir@exoplatform.com">Boubaker
 *         Khanfir</a>
 * @version $Revision$
 */
public class IDMBackup {
  
  /** The Constant LOG. */
  protected static final Log LOG = ExoLogger.getLogger(IDMBackup.class);
  
  /** The Constant CONTENT_ZIP_FILE. */
  protected static final String CONTENT_ZIP_FILE = "idm-dump.zip";
  
  /** The Constant CONTENT_LEN_ZIP_FILE. */
  protected static final String CONTENT_LEN_ZIP_FILE = "idm-dump-len.zip";
  
  /** The Constant TABLE_NAMES. */
  protected static final String[] TABLE_NAMES = { "jbid_attr_bin_value", "jbid_creden_bin_value", "jbid_io", "jbid_io_attr", "jbid_io_attr_text_values", "jbid_io_creden", "jbid_io_creden_props",
      "jbid_io_creden_type", "jbid_io_props", "jbid_io_rel", "jbid_io_rel_name", "jbid_io_rel_name_props", "jbid_io_rel_props", "jbid_io_rel_type", "jbid_io_type", "jbid_real_props", "jbid_realm" };

  /** The Constant BACKUP_USER_LISTENER. */
  private static final BackupUserListener BACKUP_USER_LISTENER = new BackupUserListener();
  
  /** The Constant BACKUP_USER_PROFILE_LISTENER. */
  private static final BackupUserProfileListener BACKUP_USER_PROFILE_LISTENER = new BackupUserProfileListener();
  
  /** The Constant BACKUP_GROUP_LISTENER. */
  private static final BackupGroupListener BACKUP_GROUP_LISTENER = new BackupGroupListener();
  
  /** The Constant BACKUP_MEMBERSHIP_LISTENER. */
  private static final BackupMembershipListener BACKUP_MEMBERSHIP_LISTENER = new BackupMembershipListener();
  
  /** The Constant BACKUP_MEMBERSHIP_TYPE_LISTENER. */
  private static final BackupMembershipTypeListener BACKUP_MEMBERSHIP_TYPE_LISTENER = new BackupMembershipTypeListener();

  /**
   * Backup.
   *
   * @param portalContainer the portal container
   * @param storageDir the storage dir
   * @throws Exception the exception
   */
  public static void backup(PortalContainer portalContainer, final File storageDir) throws Exception {
    Connection jdbcConn = null;
    try {
      addIDMBackupListeners(portalContainer);

      // using existing DataSource to get a JDBC Connection.
      SessionFactoryImpl sessionFactoryImpl = (SessionFactoryImpl) getHibernateService(portalContainer).getSessionFactory();
      jdbcConn = sessionFactoryImpl.getConnectionProvider().getConnection();

      Map<String, String> scripts = new HashMap<String, String>();
      for (String tableName : TABLE_NAMES) {
        scripts.put(tableName, "select * from " + tableName);
      }

      backup(storageDir, jdbcConn, scripts);
    } catch (Exception e) {
      LOG.error("Error while backup", e);
      throw new BackupException(e);
    } finally {
      removeIDMBackupListeners(portalContainer);
      if (jdbcConn != null && !jdbcConn.isClosed()) {
        try {
          jdbcConn.commit();
        } catch (Exception e) {
          LOG.error("Error while committing transaction", e);
        }
        try {
          jdbcConn.close();
        } catch (Exception e) {
          LOG.error("Error while closing DS connection", e);
        }
      }
    }
  }

  /**
   * Intercept IDM modification operation.
   */
  public static void interceptIDMModificationOperation() {
    if (BackupExportResource.backupInProgress) {
      if (BackupExportResource.WRITE_STRATEGY_NOTHING.equals(BackupExportResource.writeStrategy)) {
        // Nothing to do
      } else if (BackupExportResource.WRITE_STRATEGY_SUSPEND.equals(BackupExportResource.writeStrategy)) {
        do {
          try {
            Thread.sleep(2000);
          } catch (Throwable e) {
            // Nothing to do
          }
        } while (BackupExportResource.backupInProgress);
      } else if (BackupExportResource.WRITE_STRATEGY_EXCEPTION.equals(BackupExportResource.writeStrategy)) {
        throw new BackupInProgressException("Backup is in progress, the Platform is in readonly mode. Your changes are ignored.");
      }
    }
  }

  /**
   * Adds the IDM backup listeners.
   *
   * @param portalContainer the portal container
   */
  private static void addIDMBackupListeners(PortalContainer portalContainer) {
    getOrganizationService(portalContainer).getUserHandler().addUserEventListener(BACKUP_USER_LISTENER);
    getOrganizationService(portalContainer).getUserProfileHandler().addUserProfileEventListener(BACKUP_USER_PROFILE_LISTENER);
    getOrganizationService(portalContainer).getGroupHandler().addGroupEventListener(BACKUP_GROUP_LISTENER);
    getOrganizationService(portalContainer).getMembershipHandler().addMembershipEventListener(BACKUP_MEMBERSHIP_LISTENER);
    getOrganizationService(portalContainer).getMembershipTypeHandler().addMembershipTypeEventListener(BACKUP_MEMBERSHIP_TYPE_LISTENER);
  }

  /**
   * Removes the IDM backup listeners.
   *
   * @param portalContainer the portal container
   */
  private static void removeIDMBackupListeners(PortalContainer portalContainer) {
    getOrganizationService(portalContainer).getUserHandler().removeUserEventListener(BACKUP_USER_LISTENER);
    getOrganizationService(portalContainer).getUserProfileHandler().removeUserProfileEventListener(BACKUP_USER_PROFILE_LISTENER);
    getOrganizationService(portalContainer).getGroupHandler().removeGroupEventListener(BACKUP_GROUP_LISTENER);
    getOrganizationService(portalContainer).getMembershipHandler().removeMembershipEventListener(BACKUP_MEMBERSHIP_LISTENER);
    getOrganizationService(portalContainer).getMembershipTypeHandler().removeMembershipTypeEventListener(BACKUP_MEMBERSHIP_TYPE_LISTENER);
  }

  /**
   * Backup.
   *
   * @param storageDir the storage dir
   * @param jdbcConn the jdbc conn
   * @param scripts the scripts
   * @throws BackupException the backup exception
   */
  public static void backup(File storageDir, Connection jdbcConn, Map<String, String> scripts) throws BackupException {
    Exception exc = null;

    ZipObjectWriter contentWriter = null;
    ZipObjectWriter contentLenWriter = null;

    try {
      contentWriter = new ZipObjectWriter(PrivilegedFileHelper.zipOutputStream(new File(storageDir, CONTENT_ZIP_FILE)));
      contentLenWriter = new ZipObjectWriter(PrivilegedFileHelper.zipOutputStream(new File(storageDir, CONTENT_LEN_ZIP_FILE)));

      for (Entry<String, String> entry : scripts.entrySet()) {
        dumpTable(jdbcConn, entry.getKey(), entry.getValue(), contentWriter, contentLenWriter);
      }
    } catch (IOException e) {
      exc = e;
      throw new BackupException(e);
    } catch (SQLException e) {
      exc = e;
      throw new BackupException("SQL Exception: " + JDBCUtils.getFullMessage(e), e);
    } finally {
      if (jdbcConn != null) {
        try {
          jdbcConn.close();
        } catch (SQLException e) {
          if (exc != null) {
            LOG.error("Can't close connection", e);
            throw new BackupException(exc);
          } else {
            throw new BackupException(e);
          }
        }
      }

      try {
        if (contentWriter != null) {
          contentWriter.close();
        }

        if (contentLenWriter != null) {
          contentLenWriter.close();
        }
      } catch (IOException e) {
        if (exc != null) {
          LOG.error("Can't close zip", e);
          throw new BackupException(exc);
        } else {
          throw new BackupException(e);
        }
      }
    }
  }

  /**
   * Dump table.
   *
   * @param jdbcConn the jdbc conn
   * @param tableName the table name
   * @param script the script
   * @param contentWriter the content writer
   * @param contentLenWriter the content len writer
   * @throws IOException Signals that an I/O exception has occurred.
   * @throws SQLException the SQL exception
   */
  private static void dumpTable(Connection jdbcConn, String tableName, String script, ZipObjectWriter contentWriter, ZipObjectWriter contentLenWriter) throws IOException, SQLException {
    SecurityManager security = System.getSecurityManager();
    if (security != null) {
      security.checkPermission(JCRRuntimePermissions.MANAGE_REPOSITORY_PERMISSION);
    }

    Statement stmt = null;
    ResultSet rs = null;
    try {
      contentWriter.putNextEntry(new ZipEntry(tableName));
      contentLenWriter.putNextEntry(new ZipEntry(tableName));

      stmt = jdbcConn.createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
      stmt.setFetchSize(1000);
      rs = stmt.executeQuery(script);
      ResultSetMetaData metaData = rs.getMetaData();

      int columnCount = metaData.getColumnCount();
      int[] columnType = new int[columnCount];

      contentWriter.writeInt(columnCount);
      for (int i = 0; i < columnCount; i++) {
        columnType[i] = metaData.getColumnType(i + 1);
        contentWriter.writeInt(columnType[i]);
        contentWriter.writeString(metaData.getColumnName(i + 1));
      }

      byte[] tmpBuff = new byte[2048];

      // Now we can output the actual data
      while (rs.next()) {
        for (int i = 0; i < columnCount; i++) {
          InputStream value;
          if (columnType[i] == Types.VARBINARY || columnType[i] == Types.LONGVARBINARY || columnType[i] == Types.BLOB || columnType[i] == Types.BINARY || columnType[i] == Types.OTHER) {
            value = rs.getBinaryStream(i + 1);
          } else {
            String str = rs.getString(i + 1);
            value = str == null ? null : new ByteArrayInputStream(str.getBytes(Constants.DEFAULT_ENCODING));
          }

          if (value == null) {
            contentLenWriter.writeLong(-1);
          } else {
            long len = 0;
            int read = 0;

            while ((read = value.read(tmpBuff)) >= 0) {
              contentWriter.write(tmpBuff, 0, read);
              len += read;
            }
            contentLenWriter.writeLong(len);
          }
        }
      }

      contentWriter.closeEntry();
      contentLenWriter.closeEntry();
    } finally {
      if (rs != null) {
        rs.close();
      }

      if (stmt != null) {
        stmt.close();
      }
    }
  }

  /**
   * Gets the hibernate service.
   *
   * @param portalContainer the portal container
   * @return the hibernate service
   */
  private static HibernateService getHibernateService(PortalContainer portalContainer) {
    return (HibernateService) portalContainer.getComponentInstanceOfType(HibernateService.class);
  }

  /**
   * Gets the organization service.
   *
   * @param portalContainer the portal container
   * @return the organization service
   */
  private static OrganizationService getOrganizationService(PortalContainer portalContainer) {
    return (OrganizationService) portalContainer.getComponentInstanceOfType(OrganizationService.class);
  }
}
