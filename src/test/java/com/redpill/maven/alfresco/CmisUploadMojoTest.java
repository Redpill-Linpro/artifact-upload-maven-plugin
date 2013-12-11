package com.redpill.maven.alfresco;

import java.net.MalformedURLException;
import java.net.URL;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.Test;

public class CmisUploadMojoTest {

  @Test
  public void testExecute() throws MalformedURLException, MojoExecutionException {
    final CmisUploadMojo mojo = new CmisUploadMojo();

    mojo.setUsername("admin");
    mojo.setPassword("admin");
    mojo.setUrl(new URL("http://localhost:8080/alfresco/service/cmis"));
    mojo.setSite("demo");
    mojo.setPath("Clients/VGR/Installation");

    // mojo.execute();
  }

}
