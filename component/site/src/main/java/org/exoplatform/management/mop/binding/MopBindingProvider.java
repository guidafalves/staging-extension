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

package org.exoplatform.management.mop.binding;

import org.exoplatform.management.mop.binding.xml.NavigationMarshaller;
import org.exoplatform.management.mop.binding.xml.PageMarshaller;
import org.exoplatform.management.mop.binding.xml.SiteLayoutMarshaller;
import org.exoplatform.portal.config.model.Page;
import org.exoplatform.portal.config.model.PageNavigation;
import org.exoplatform.portal.config.model.PortalConfig;
import org.gatein.management.api.ContentType;
import org.gatein.management.api.binding.BindingException;
import org.gatein.management.api.binding.BindingProvider;
import org.gatein.management.api.binding.Marshaller;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * The Class MopBindingProvider.
 *
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 * @version $Revision$
 */
public class MopBindingProvider implements BindingProvider {
  
  /** The Constant INSTANCE. */
  public static final MopBindingProvider INSTANCE = new MopBindingProvider();

  /**
   * Instantiates a new mop binding provider.
   */
  private MopBindingProvider() {}

  /**
   * {@inheritDoc}
   */
  @Override
  public <T> Marshaller<T> getMarshaller(Class<T> type, ContentType contentType) throws BindingException {
    switch (contentType) {
    case XML:
      return getXmlMarshaller(type);
    case JSON:
    case ZIP:
    default:
      return null;
    }
  }

  /**
   * Gets the xml marshaller.
   *
   * @param <T> the generic type
   * @param type the type
   * @return the xml marshaller
   */
  @SuppressWarnings("unchecked")
  private <T> Marshaller<T> getXmlMarshaller(Class<T> type) {
    if (Page.class.isAssignableFrom(type)) {
      return (Marshaller<T>) XmlMarshallers.page_marshaller;
    } else if (Page.PageSet.class.isAssignableFrom(type)) {
      return (Marshaller<T>) XmlMarshallers.pages_marshaller;
    } else if (PageNavigation.class.isAssignableFrom(type)) {
      return (Marshaller<T>) XmlMarshallers.navigation_marshaller;
    } else if (PortalConfig.class.isAssignableFrom(type)) {
      return (Marshaller<T>) XmlMarshallers.site_marshaller;
    }

    return null;
  }

  /**
   * The Class XmlMarshallers.
   */
  private static class XmlMarshallers {

    // ------------------------------------ Page Marshallers
    /** The pages marshaller. */
    // ------------------------------------//
    private static Marshaller<Page.PageSet> pages_marshaller = new PageMarshaller();

    /** The page marshaller. */
    private static Marshaller<Page> page_marshaller = new Marshaller<Page>() {

      public void marshal(Page object, OutputStream outputStream, boolean pretty) throws BindingException {
        marshal(object, outputStream);
      }

      public void marshal(Page page, OutputStream outputStream) throws BindingException {
        Page.PageSet pages = new Page.PageSet();
        pages.setPages(new ArrayList<Page>(1));
        pages.getPages().add(page);

        XmlMarshallers.pages_marshaller.marshal(pages, outputStream, false);
      }

      @Override
      public Page unmarshal(InputStream inputStream) throws BindingException {
        Page.PageSet pages = pages_marshaller.unmarshal(inputStream);

        if (pages.getPages().isEmpty())
          throw new BindingException("No page was unmarshalled.");

        if (pages.getPages().size() != 1)
          throw new BindingException("Multiple pages found.");

        return pages.getPages().get(0);
      }
    };

    /** The navigation marshaller. */
    private static Marshaller<PageNavigation> navigation_marshaller = new NavigationMarshaller();

    /** The site marshaller. */
    private static Marshaller<PortalConfig> site_marshaller = new SiteLayoutMarshaller();
  }
}
