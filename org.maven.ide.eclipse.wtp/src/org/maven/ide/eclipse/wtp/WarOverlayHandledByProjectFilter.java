/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.wtp;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jdt.core.IClasspathEntry;
import org.maven.ide.eclipse.jdt.IClasspathDescriptor;
import org.maven.ide.eclipse.jdt.IClasspathEntryDescriptor;


/**
 * Filter to remove the second WAR-OVERLAY artifact when it has been handled by a project
 *
 * @author webb_p
 */
public class WarOverlayHandledByProjectFilter implements ArtifactFilter {

  private final IClasspathDescriptor classpath;

  public WarOverlayHandledByProjectFilter(final IClasspathDescriptor classpath) {
    this.classpath = classpath;
  }

  public boolean include(Artifact artifact) {
    if("war-overlay".equals(artifact.getType())) {
      for(IClasspathEntryDescriptor descriptor : classpath.getEntryDescriptors()) {
        IClasspathEntry entry = descriptor.getClasspathEntry();
        if(entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
          if(StringUtils.equals(artifact.getGroupId(), descriptor.getGroupId())
              && StringUtils.equals(artifact.getArtifactId(), descriptor.getArtifactId())) {
            return false;
          }
        }
      }
    }
    return true;
  }
}