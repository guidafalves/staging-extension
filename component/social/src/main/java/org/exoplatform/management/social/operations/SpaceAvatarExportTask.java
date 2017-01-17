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
package org.exoplatform.management.social.operations;

import com.thoughtworks.xstream.XStream;

import org.exoplatform.social.core.model.AvatarAttachment;
import org.gatein.management.api.operation.model.ExportTask;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

/**
 * The Class SpaceAvatarExportTask.
 */
public class SpaceAvatarExportTask implements ExportTask {
  
  /** The Constant FILENAME. */
  public static final String FILENAME = "spaceAvatar.metadata";
  
  /** The space pretty name. */
  private String spacePrettyName;
  
  /** The avatar attachment. */
  private AvatarAttachment avatarAttachment;

  /**
   * Instantiates a new space avatar export task.
   *
   * @param spacePrettyName the space pretty name
   * @param avatarAttachment the avatar attachment
   */
  public SpaceAvatarExportTask(String spacePrettyName, AvatarAttachment avatarAttachment) {
    this.spacePrettyName = spacePrettyName;
    this.avatarAttachment = avatarAttachment;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getEntry() {
    return new StringBuilder("social/space/").append(spacePrettyName).append("/").append(FILENAME).toString();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void export(OutputStream outputStream) throws IOException {
    XStream xStream = new XStream();
    OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8");
    xStream.toXML(avatarAttachment, writer);
    writer.flush();
  }

}
