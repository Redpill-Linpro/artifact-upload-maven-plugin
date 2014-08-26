package com.redpill.maven.alfresco;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.api.SessionFactory;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Goal which uploads an artifact to a pre-configured CMIS repository. Does not
 * upload the .pom file.
 */
@Mojo(name = "upload", defaultPhase = LifecyclePhase.INSTALL, requiresProject = true)
public class CmisUploadMojo extends AbstractMojo {

  public static final String DEFAULT_USERNAME = "admin";

  public static final String DEFAULT_PASSWORD = "admin";

  public static final String DEFAULT_MIMETYPE = "application/octet-stream";

  /**
   * The username to authenticate the call for.
   */
  @Parameter(property = "username", defaultValue = DEFAULT_USERNAME, alias = "username")
  private String _username;

  /**
   * The password to authenticate the call for.
   */
  @Parameter(property = "password", defaultValue = DEFAULT_PASSWORD, alias = "password")
  private String _password;

  /**
   * The URL to send the POST to.
   */
  @Parameter(property = "url", required = true, alias = "url")
  private URL _url;

  /**
   * The site to upload the file to.
   */
  @Parameter(property = "site", alias = "site", required = true)
  private String _site;

  /**
   * The path to upload the document to.
   */
  @Parameter(property = "path", alias = "path", required = true)
  private String _path;

  /**
   * The mimetype that the uploaded file will get.
   */
  @Parameter(property = "mimetype", alias = "mimetype", required = true, defaultValue = DEFAULT_MIMETYPE)
  private String _mimetype;

  @Parameter(property = "artifact", alias = "artifact", defaultValue = "${project.artifact}", required = true, readonly = true)
  private Artifact _artifact;

  @Parameter(property = "artifactPath", alias = "artifactPath", required = false, readonly = true)
  private String _artifactPath = null;

  /**
   * If the file can be overwritten or not
   */
  @Parameter(property = "overwrite", alias = "overwrite", defaultValue = "false", required = true)
  private boolean _overwrite;

  private Session _session;

  @Override
  public void execute() throws MojoExecutionException {
    _session = createSession();

    Folder site = findSite();

    if (site == null) {
      getLog().warn("The site was not found.");

      return;
    }

    String path = site.getPath() + "/documentLibrary/" + _path;

    try {
      File file = _artifact.getFile();

      // if the parameter artifactPath is passed, try to resolve it
      if (StringUtils.isNotBlank(_artifactPath)) {
        file = new File(_artifactPath);

        if (!file.exists()) {
          getLog().warn("Artifact path '" + _artifactPath + "' passed does not point to an existing file.");

          return;
        }
      }

      if (file == null) {
        return;
      }

      // dont' do anything with .pom files...
      if (file.getName().endsWith(".pom")) {
        return;
      }

      Folder destinationFolder = (Folder) _session.getObjectByPath(path);

      if (_overwrite) {
        deleteExistingFile(destinationFolder, file.getName());
      }

      uploadFile(path, file, destinationFolder);
    } catch (CmisObjectNotFoundException ex) {
      getLog().error("The path to save the artifact to ('" + _path + "') is not found.", ex);
    } catch (FileNotFoundException ex) {
      getLog().error("Could not open the file to be uploaded.", ex);
    } catch (CmisRuntimeException ex) {
      ex.printStackTrace();
    }
  }

  private void uploadFile(String path, File file, Folder destinationFolder) throws FileNotFoundException {
    for (int x = 0; x < 3; x++) {
      try {
        Map<String, Serializable> properties = new HashMap<String, Serializable>();

        properties.put(PropertyIds.NAME, file.getName());
        properties.put(PropertyIds.OBJECT_TYPE_ID, "cmis:document");

        ContentStream contentStream = new ContentStreamImpl(file.getAbsolutePath(), BigInteger.valueOf(file.length()), _mimetype, new FileInputStream(file));

        _session.clear();

        Document document = destinationFolder.createDocument(properties, contentStream, VersioningState.MAJOR);

        getLog().info("Uploaded '" + file.getName() + "' to '" + path + "' with a document id of '" + document.getId() + "'");

        return;
      } catch (Exception ex) {
        getLog().debug("Upload failed, retrying...");
      }
    }
  }

  private void deleteExistingFile(Folder destinationFolder, String name) {
    String path = destinationFolder.getPath() + "/" + name;

    try {
      Document document = (Document) _session.getObjectByPath(path);

      getLog().info("Deleting '" + path + "' before uploading new one.");

      document.deleteAllVersions();
    } catch (Exception ex) {
    }
  }

  private Folder findSite() {
    if (StringUtils.isBlank(_site)) {
      getLog().warn("You must supply a site name.");

      return null;
    }

    String query = "select * from st:site as s join cm:titled as t on s.cmis:objectId=t.cmis:objectId where s.cmis:name='" + _site + "'";

    ItemIterable<QueryResult> result = _session.query(query, false);

    for (QueryResult queryResult : result) {
      Folder site = (Folder) _session.getObject(queryResult.getPropertyById("cmis:objectId").getFirstValue().toString());

      return site;
    }

    return null;
  }

  private Session createSession() {
    Map<String, String> parameter = new HashMap<String, String>();

    // user credentials
    parameter.put(SessionParameter.USER, _username);
    parameter.put(SessionParameter.PASSWORD, _password);

    // connection settings
    parameter.put(SessionParameter.ATOMPUB_URL, _url.toExternalForm());
    parameter.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());

    // create session
    SessionFactory factory = SessionFactoryImpl.newInstance();

    List<Repository> repositories = factory.getRepositories(parameter);

    Repository repository = repositories.get(0);

    return repository.createSession();
  }

  public void setUsername(String username) {
    _username = username;
  }

  public void setPassword(String password) {
    _password = password;
  }

  public void setUrl(URL url) {
    _url = url;
  }

  public void setPath(String path) {
    _path = path;
  }

  public void setSite(String site) {
    _site = site;
  }

}
