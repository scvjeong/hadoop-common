/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import static org.apache.hadoop.hdfs.server.common.HdfsServerConstants.StartupOption.IMPORT;
import static org.apache.hadoop.hdfs.server.common.Util.fileAsURI;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.fs.permission.PermissionStatus;

import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.server.common.Storage.StorageDirectory;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeDirType;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeFile;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.hdfs.util.MD5FileUtils;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.util.StringUtils;
import org.junit.Assert;

/**
 * Startup and checkpoint tests
 * 
 */
public class TestStartup extends TestCase {
  public static final String NAME_NODE_HOST = "localhost:";
  public static final String WILDCARD_HTTP_HOST = "0.0.0.0:";
  private static final Log LOG =
    LogFactory.getLog(TestStartup.class.getName());
  private Configuration config;
  private File hdfsDir=null;
  static final long seed = 0xAAAAEEFL;
  static final int blockSize = 4096;
  static final int fileSize = 8192;
  private long editsLength=0, fsimageLength=0;


  private void writeFile(FileSystem fileSys, Path name, int repl)
  throws IOException {
    FSDataOutputStream stm = fileSys.create(name, true,
        fileSys.getConf().getInt("io.file.buffer.size", 4096),
        (short)repl, (long)blockSize);
    byte[] buffer = new byte[fileSize];
    Random rand = new Random(seed);
    rand.nextBytes(buffer);
    stm.write(buffer);
    stm.close();
  }


  protected void setUp() throws Exception {
    config = new HdfsConfiguration();
    hdfsDir = new File(MiniDFSCluster.getBaseDirectory());

    if ( hdfsDir.exists() && !FileUtil.fullyDelete(hdfsDir) ) {
      throw new IOException("Could not delete hdfs directory '" + hdfsDir + "'");
    }
    LOG.info("--hdfsdir is " + hdfsDir.getAbsolutePath());
    config.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        fileAsURI(new File(hdfsDir, "name")).toString());
    config.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_KEY,
        new File(hdfsDir, "data").getPath());
    config.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_DIR_KEY,
        fileAsURI(new File(hdfsDir, "secondary")).toString());
    config.set(DFSConfigKeys.DFS_NAMENODE_SECONDARY_HTTP_ADDRESS_KEY,
	       WILDCARD_HTTP_HOST + "0");
    
    FileSystem.setDefaultUri(config, "hdfs://"+NAME_NODE_HOST + "0");
  }

  /**
   * clean up
   */
  public void tearDown() throws Exception {
    if ( hdfsDir.exists() && !FileUtil.fullyDelete(hdfsDir) ) {
      throw new IOException("Could not delete hdfs directory in tearDown '" + hdfsDir + "'");
    }	
  }

   /**
   * start MiniDFScluster, create a file (to create edits) and do a checkpoint  
   * @throws IOException
   */
  @SuppressWarnings("deprecation")
  public void createCheckPoint() throws IOException {
    LOG.info("--starting mini cluster");
    // manage dirs parameter set to false 
    MiniDFSCluster cluster = null;
    SecondaryNameNode sn = null;
    
    try {
      cluster = new MiniDFSCluster.Builder(config)
                                  .manageDataDfsDirs(false)
                                  .manageNameDfsDirs(false).build();
      cluster.waitActive();

      LOG.info("--starting Secondary Node");

      // start secondary node
      sn = new SecondaryNameNode(config);
      assertNotNull(sn);

      // create a file
      FileSystem fileSys = cluster.getFileSystem();
      Path file1 = new Path("t1");
      this.writeFile(fileSys, file1, 1);

      LOG.info("--doing checkpoint");
      sn.doCheckpoint();  // this shouldn't fail
      LOG.info("--done checkpoint");
    } catch (IOException e) {
      fail(StringUtils.stringifyException(e));
      System.err.println("checkpoint failed");
      throw e;
    }  finally {
      if(sn!=null)
        sn.shutdown();
      if(cluster!=null) 
        cluster.shutdown();
      LOG.info("--file t1 created, cluster shutdown");
    }
  }

  /*
   * corrupt files by removing and recreating the directory
   */
  private void corruptNameNodeFiles() throws IOException {
    // now corrupt/delete the directrory
    List<URI> nameDirs = (List<URI>)FSNamesystem.getNamespaceDirs(config);
    List<URI> nameEditsDirs = (List<URI>)FSNamesystem.getNamespaceEditsDirs(config);

    // get name dir and its length, then delete and recreate the directory
    File dir = new File(nameDirs.get(0).getPath()); // has only one
    this.fsimageLength = new File(new File(dir, "current"), 
        NameNodeFile.IMAGE.getName()).length();

    if(dir.exists() && !(FileUtil.fullyDelete(dir)))
      throw new IOException("Cannot remove directory: " + dir);

    LOG.info("--removed dir "+dir + ";len was ="+ this.fsimageLength);

    if (!dir.mkdirs())
      throw new IOException("Cannot create directory " + dir);

    dir = new File( nameEditsDirs.get(0).getPath()); //has only one

    this.editsLength = new File(new File(dir, "current"), 
        NameNodeFile.EDITS.getName()).length();

    if(dir.exists() && !(FileUtil.fullyDelete(dir)))
      throw new IOException("Cannot remove directory: " + dir);
    if (!dir.mkdirs())
      throw new IOException("Cannot create directory " + dir);

    LOG.info("--removed dir and recreated "+dir + ";len was ="+ this.editsLength);


  }

  /**
   * start with -importCheckpoint option and verify that the files are in separate directories and of the right length
   * @throws IOException
   */
  private void checkNameNodeFiles() throws IOException{

    // start namenode with import option
    LOG.info("-- about to start DFS cluster");
    MiniDFSCluster cluster = null;
    try {
      cluster = new MiniDFSCluster.Builder(config)
                                  .format(false)
                                  .manageDataDfsDirs(false)
                                  .manageNameDfsDirs(false)
                                  .startupOption(IMPORT).build();
      cluster.waitActive();
      LOG.info("--NN started with checkpoint option");
      NameNode nn = cluster.getNameNode();
      assertNotNull(nn);	
      // Verify that image file sizes did not change.
      FSImage image = nn.getFSImage();
      verifyDifferentDirs(image, this.fsimageLength, this.editsLength);
    } finally {
      if(cluster != null)
        cluster.shutdown();
    }
  }

  /**
   * verify that edits log and fsimage are in different directories and of a correct size
   */
  private void verifyDifferentDirs(FSImage img, long expectedImgSize, long expectedEditsSize) {
    StorageDirectory sd =null;
    for (Iterator<StorageDirectory> it = img.getStorage().dirIterator(); it.hasNext();) {
      sd = it.next();

      if(sd.getStorageDirType().isOfType(NameNodeDirType.IMAGE)) {
        File imf = img.getStorage().getStorageFile(sd, NameNodeFile.IMAGE, 0);
        LOG.info("--image file " + imf.getAbsolutePath() + "; len = " + imf.length() + "; expected = " + expectedImgSize);
        assertEquals(expectedImgSize, imf.length());	
      } else if(sd.getStorageDirType().isOfType(NameNodeDirType.EDITS)) {
        File edf = img.getStorage().getStorageFile(sd, NameNodeFile.EDITS, 0);
        LOG.info("-- edits file " + edf.getAbsolutePath() + "; len = " + edf.length()  + "; expected = " + expectedEditsSize);
        assertEquals(expectedEditsSize, edf.length());	
      } else {
        fail("Image/Edits directories are not different");
      }
    }

  }
  /**
   * secnn-6
   * checkpoint for edits and image is the same directory
   * @throws IOException
   */
  public void testChkpointStartup2() throws IOException{
    LOG.info("--starting checkpointStartup2 - same directory for checkpoint");
    // different name dirs
    config.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        fileAsURI(new File(hdfsDir, "name")).toString());
    config.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
        fileAsURI(new File(hdfsDir, "edits")).toString());
    // same checkpoint dirs
    config.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_EDITS_DIR_KEY,
        fileAsURI(new File(hdfsDir, "chkpt")).toString());
    config.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_DIR_KEY,
        fileAsURI(new File(hdfsDir, "chkpt")).toString());

    createCheckPoint();

    corruptNameNodeFiles();
    checkNameNodeFiles();

  }

  /**
   * seccn-8
   * checkpoint for edits and image are different directories 
   * @throws IOException
   */
  public void testChkpointStartup1() throws IOException{
    //setUpConfig();
    LOG.info("--starting testStartup Recovery");
    // different name dirs
    config.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        fileAsURI(new File(hdfsDir, "name")).toString());
    config.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
        fileAsURI(new File(hdfsDir, "edits")).toString());
    // same checkpoint dirs
    config.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_EDITS_DIR_KEY,
        fileAsURI(new File(hdfsDir, "chkpt_edits")).toString());
    config.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_DIR_KEY,
        fileAsURI(new File(hdfsDir, "chkpt")).toString());

    createCheckPoint();
    corruptNameNodeFiles();
    checkNameNodeFiles();
  }

  /**
   * secnn-7
   * secondary node copies fsimage and edits into correct separate directories.
   * @throws IOException
   */
  @SuppressWarnings("deprecation")
  public void testSNNStartup() throws IOException{
    //setUpConfig();
    LOG.info("--starting SecondNN startup test");
    // different name dirs
    config.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        fileAsURI(new File(hdfsDir, "name")).toString());
    config.set(DFSConfigKeys.DFS_NAMENODE_EDITS_DIR_KEY,
        fileAsURI(new File(hdfsDir, "name")).toString());
    // same checkpoint dirs
    config.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_EDITS_DIR_KEY,
        fileAsURI(new File(hdfsDir, "chkpt_edits")).toString());
    config.set(DFSConfigKeys.DFS_NAMENODE_CHECKPOINT_DIR_KEY,
        fileAsURI(new File(hdfsDir, "chkpt")).toString());

    LOG.info("--starting NN ");
    MiniDFSCluster cluster = null;
    SecondaryNameNode sn = null;
    NameNode nn = null;
    try {
      cluster = new MiniDFSCluster.Builder(config).manageDataDfsDirs(false)
                                                  .manageNameDfsDirs(false)
                                                  .build();
      cluster.waitActive();
      nn = cluster.getNameNode();
      assertNotNull(nn);

      // start secondary node
      LOG.info("--starting SecondNN");
      sn = new SecondaryNameNode(config);
      assertNotNull(sn);

      LOG.info("--doing checkpoint");
      sn.doCheckpoint();  // this shouldn't fail
      LOG.info("--done checkpoint");



      // now verify that image and edits are created in the different directories
      FSImage image = nn.getFSImage();
      StorageDirectory sd = image.getStorage().getStorageDir(0); //only one
      assertEquals(sd.getStorageDirType(), NameNodeDirType.IMAGE_AND_EDITS);
      File imf = image.getStorage().getStorageFile(sd, NameNodeFile.IMAGE, 0);
      File edf = image.getStorage().getStorageFile(sd, NameNodeFile.EDITS, 0);
      LOG.info("--image file " + imf.getAbsolutePath() + "; len = " + imf.length());
      LOG.info("--edits file " + edf.getAbsolutePath() + "; len = " + edf.length());

      FSImage chkpImage = sn.getFSImage();
      verifyDifferentDirs(chkpImage, imf.length(), edf.length());

    } catch (IOException e) {
      fail(StringUtils.stringifyException(e));
      System.err.println("checkpoint failed");
      throw e;
    } finally {
      if(sn!=null)
        sn.shutdown();
      if(cluster!=null)
        cluster.shutdown();
    }
  }
  
  public void testCompression() throws IOException {
    LOG.info("Test compressing image.");
    Configuration conf = new Configuration();
    FileSystem.setDefaultUri(conf, "hdfs://localhost:0");
    conf.set(DFSConfigKeys.DFS_NAMENODE_HTTP_ADDRESS_KEY, "127.0.0.1:0");
    File base_dir = new File(System.getProperty(
        "test.build.data", "build/test/data"), "dfs/");
    conf.set(DFSConfigKeys.DFS_NAMENODE_NAME_DIR_KEY,
        new File(base_dir, "name").getPath());
    conf.setBoolean(DFSConfigKeys.DFS_PERMISSIONS_ENABLED_KEY, false);

    DFSTestUtil.formatNameNode(conf);

    // create an uncompressed image
    LOG.info("Create an uncompressed fsimage");
    NameNode namenode = new NameNode(conf);
    namenode.getNamesystem().mkdirs("/test",
        new PermissionStatus("hairong", null, FsPermission.getDefault()), true);
    NamenodeProtocols nnRpc = namenode.getRpcServer();
    assertTrue(nnRpc.getFileInfo("/test").isDir());
    nnRpc.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    nnRpc.saveNamespace();
    namenode.stop();
    namenode.join();

    // compress image using default codec
    LOG.info("Read an uncomressed image and store it compressed using default codec.");
    conf.setBoolean(DFSConfigKeys.DFS_IMAGE_COMPRESS_KEY, true);
    checkNameSpace(conf);

    // read image compressed using the default and compress it using Gzip codec
    LOG.info("Read a compressed image and store it using a different codec.");
    conf.set(DFSConfigKeys.DFS_IMAGE_COMPRESSION_CODEC_KEY,
        "org.apache.hadoop.io.compress.GzipCodec");
    checkNameSpace(conf);

    // read an image compressed in Gzip and store it uncompressed
    LOG.info("Read an compressed iamge and store it as uncompressed.");
    conf.setBoolean(DFSConfigKeys.DFS_IMAGE_COMPRESS_KEY, false);
    checkNameSpace(conf);

    // read an uncomrpessed image and store it uncompressed
    LOG.info("Read an uncompressed image and store it as uncompressed.");
    checkNameSpace(conf);
  }

  private void checkNameSpace(Configuration conf) throws IOException {
    NameNode namenode = new NameNode(conf);
    NamenodeProtocols nnRpc = namenode.getRpcServer();
    assertTrue(nnRpc.getFileInfo("/test").isDir());
    nnRpc.setSafeMode(SafeModeAction.SAFEMODE_ENTER);
    nnRpc.saveNamespace();
    namenode.stop();
    namenode.join();
  }
  
  public void testImageChecksum() throws Exception {
    LOG.info("Test uncompressed image checksum");
    testImageChecksum(false);
    LOG.info("Test compressed image checksum");
    testImageChecksum(true);
  }

  private void testImageChecksum(boolean compress) throws Exception {
    MiniDFSCluster cluster = null;
    Configuration conf = new HdfsConfiguration();
    if (compress) {
      conf.setBoolean(DFSConfigKeys.DFS_IMAGE_COMPRESSION_CODEC_KEY, true);
    }

    try {
        LOG.info("\n===========================================\n" +
                 "Starting empty cluster");
        
        cluster = new MiniDFSCluster.Builder(conf)
          .numDataNodes(0)
          .format(true)
          .build();
        cluster.waitActive();
        
        FileSystem fs = cluster.getFileSystem();
        fs.mkdirs(new Path("/test"));
        
        // Directory layout looks like:
        // test/data/dfs/nameN/current/{fsimage,edits,...}
        File nameDir = new File(cluster.getNameDirs(0).iterator().next().getPath());
        File dfsDir = nameDir.getParentFile();
        assertEquals(dfsDir.getName(), "dfs"); // make sure we got right dir
        
        LOG.info("Shutting down cluster #1");
        cluster.shutdown();
        cluster = null;

        // Corrupt the md5 file to all 0s
        File imageFile = new File(nameDir, "current/" + NNStorage.getImageFileName(0));
        MD5FileUtils.saveMD5File(imageFile, new MD5Hash(new byte[16]));
        
        // Try to start a new cluster
        LOG.info("\n===========================================\n" +
        "Starting same cluster after simulated crash");
        try {
          cluster = new MiniDFSCluster.Builder(conf)
            .numDataNodes(0)
            .format(false)
            .build();
          fail("Should not have successfully started with corrupt image");
        } catch (IOException ioe) {
          if (!ioe.getCause().getMessage().contains("is corrupt with MD5")) {
            throw ioe;
          }
        }
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
  
  /**
   * This test tests hosts include list contains host names.  After namenode
   * restarts, the still alive datanodes should not have any trouble in getting
   * registrant again.
   */
  public void testNNRestart() throws IOException, InterruptedException {
    MiniDFSCluster cluster = null;
    FileSystem localFileSys;
    Path hostsFile;
    Path excludeFile;
    Configuration conf = new HdfsConfiguration();
    int HEARTBEAT_INTERVAL = 1; // heartbeat interval in seconds
    // Set up the hosts/exclude files.
    localFileSys = FileSystem.getLocal(conf);
    Path workingDir = localFileSys.getWorkingDirectory();
    Path dir = new Path(workingDir, "build/test/data/work-dir/restartnn");
    hostsFile = new Path(dir, "hosts");
    excludeFile = new Path(dir, "exclude");

    // Setup conf
    conf.set(DFSConfigKeys.DFS_HOSTS_EXCLUDE, excludeFile.toUri().getPath());
    writeConfigFile(localFileSys, excludeFile, null);    
    conf.set(DFSConfigKeys.DFS_HOSTS, hostsFile.toUri().getPath());
    // write into hosts file
    ArrayList<String>list = new ArrayList<String>();
    byte b[] = {127, 0, 0, 1};
    InetAddress inetAddress = InetAddress.getByAddress(b);
    list.add(inetAddress.getHostName());
    writeConfigFile(localFileSys, hostsFile, list);
    int numNameNodes = 1;
    int numDatanodes = 1;
    
    try {
      cluster = new MiniDFSCluster.Builder(conf).numNameNodes(numNameNodes)
      .numDataNodes(numDatanodes).setupHostsFile(true).build();
      cluster.waitActive();
  
      cluster.restartNameNode();
      NamenodeProtocols nn = cluster.getNameNodeRpc();
      assertNotNull(nn);
      Assert.assertTrue(cluster.isDataNodeUp());
      
      DatanodeInfo[] info = nn.getDatanodeReport(DatanodeReportType.LIVE);
      for (int i = 0 ; i < 5 && info.length != numDatanodes; i++) {
        Thread.sleep(HEARTBEAT_INTERVAL * 1000);
        info = nn.getDatanodeReport(DatanodeReportType.LIVE);
      }
      assertEquals("Number of live nodes should be "+numDatanodes, numDatanodes, 
          info.length);
      
    } catch (IOException e) {
      fail(StringUtils.stringifyException(e));
      throw e;
    } finally {
      cleanupFile(localFileSys, excludeFile.getParent());
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }
  
  private void writeConfigFile(FileSystem localFileSys, Path name,
      ArrayList<String> nodes) throws IOException {
    // delete if it already exists
    if (localFileSys.exists(name)) {
      localFileSys.delete(name, true);
    }

    if (nodes != null) {
      FSDataOutputStream stm = localFileSys.create(name);
      for (Iterator<String> it = nodes.iterator(); it.hasNext();) {
        String node = it.next();
        stm.writeBytes(node);
        stm.writeBytes("\n");
      }
      stm.close();
    }
  }
  
  private void cleanupFile(FileSystem fileSys, Path name) throws IOException {
    assertTrue(fileSys.exists(name));
    fileSys.delete(name, true);
    assertTrue(!fileSys.exists(name));
  }
}