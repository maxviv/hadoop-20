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
package org.apache.hadoop.raid;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.RaidDFSUtil;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.hadoop.raid.protocol.PolicyInfo;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.raid.PlacementMonitor.BlockInfo;

/**
  * Test the generation of parity blocks for files with different block
  * sizes. Also test that a data block can be regenerated from a raid stripe
  * using the parity block
  */
public class TestRaidNode extends TestCase {
  final static String TEST_DIR = new File(System.getProperty("test.build.data",
      "build/contrib/raid/test/data")).getAbsolutePath();
  final static String CONFIG_FILE = new File(TEST_DIR, 
      "test-raid.xml").getAbsolutePath();
  final static long RELOAD_INTERVAL = 1000;
  final static Log LOG = LogFactory.getLog("org.apache.hadoop.raid.TestRaidNode");
  final static Random rand = new Random();

  Configuration conf;
  String namenode = null;
  String hftp = null;
  MiniDFSCluster dfs = null;
  MiniMRCluster mr = null;
  FileSystem fileSys = null;
  String jobTrackerName = null;

  /**
   * create mapreduce and dfs clusters
   */
  private void createClusters(boolean local) throws Exception {
    if (System.getProperty("hadoop.log.dir") == null) {
      String base = new File(".").getAbsolutePath();
      System.setProperty("hadoop.log.dir", new Path(base).toString() + "/logs");
    }

    new File(TEST_DIR).mkdirs(); // Make sure data directory exists
    conf = new Configuration();
    conf.set("raid.config.file", CONFIG_FILE);
    conf.set(RaidNode.RAID_LOCATION_KEY, "/destraid");
    conf.setBoolean("raid.config.reload", true);
    conf.setLong("raid.config.reload.interval", RELOAD_INTERVAL);

    // scan all policies once every 100 second
    conf.setLong("raid.policy.rescan.interval", 100 * 1000L);

    // the RaidNode does the raiding inline (instead of submitting to map/reduce)
    if (local) {
      conf.set("raid.classname", "org.apache.hadoop.raid.LocalRaidNode");
    } else {
      conf.set("raid.classname", "org.apache.hadoop.raid.DistRaidNode");
    }

    // use local block fixer
    conf.set("raid.blockfix.classname", 
             "org.apache.hadoop.raid.LocalBlockIntegrityMonitor");
    conf.set("dfs.block.replicator.classname",
        "org.apache.hadoop.hdfs.server.namenode.BlockPlacementPolicyRaid");

    conf.set("raid.server.address", "localhost:0");

    // create a dfs and map-reduce cluster
    final int taskTrackers = 4;
    final int jobTrackerPort = 60050;

    // Because BlockPlacementPolicyRaid only allows one replica in each rack,
    // spread 6 nodes into 6 racks to make sure chooseTarget function could pick
    // more than one node. 
    String[] racks = {"/rack1", "/rack2", "/rack3", "/rack4", "/rack5", "/rack6"};
    dfs = new MiniDFSCluster(conf, 6, true, racks);
    dfs.waitActive();
    fileSys = dfs.getFileSystem();
    namenode = fileSys.getUri().toString();
    mr = new MiniMRCluster(taskTrackers, namenode, 3);
    jobTrackerName = "localhost:" + mr.getJobTrackerPort();
    hftp = "hftp://localhost.localdomain:" + dfs.getNameNodePort();

    FileSystem.setDefaultUri(conf, namenode);
    conf.set("mapred.job.tracker", jobTrackerName);
  }

  static class ConfigBuilder {
    private List<String> policies;

    public ConfigBuilder() {
      policies = new java.util.ArrayList<String>();
    }

    public void addFileListPolicy(String name, String fileListPath, String parent) {
      String str =
        "<policy name = \"" + name + "\"> " +
          "<fileList>" + fileListPath + "</fileList>" +
          "<parentPolicy>" + parent + "</parentPolicy>" +
        "</policy>";
      policies.add(str);
    }

    public void addPolicy(String name, String path, String parent) {
      String str =
        "<policy name = \"" + name + "\"> " +
          "<srcPath prefix=\"" + path + "\"/> " +
          "<parentPolicy>" + parent + "</parentPolicy>" +
        "</policy>";
      policies.add(str);
    }

    public void addPolicy(String name, short srcReplication,
                          long targetReplication, long metaReplication, long stripeLength) {
      String str =
          "<policy name = \"" + name + "\"> " +
             "<srcPath/> " +
             "<erasureCode>xor</erasureCode> " +
             "<property> " +
               "<name>srcReplication</name> " +
               "<value>" + srcReplication + "</value> " +
               "<description> pick only files whole replFactor is greater than or equal to " +
               "</description> " +
             "</property> " +
             "<property> " +
               "<name>targetReplication</name> " +
               "<value>" + targetReplication + "</value> " +
               "<description>after RAIDing, decrease the replication factor of a file to this value." +
               "</description> " +
             "</property> " +
             "<property> " +
               "<name>metaReplication</name> " +
               "<value>" + metaReplication + "</value> " +
               "<description> replication factor of parity file" +
               "</description> " +
             "</property> " +
             "<property> " +
               "<name>stripeLength</name> " +
               "<value>" + stripeLength + "</value> " +
               "<description> the max number of blocks in a file to RAID together " +
               "</description> " +
             "</property> " +
             "<property> " +
               "<name>modTimePeriod</name> " +
               "<value>2000</value> " +
               "<description> time (milliseconds) after a file is modified to make it " +
                              "a candidate for RAIDing " +
               "</description> " +
             "</property> " +
          "</policy>";
      policies.add(str);
    }
    
    public void addPolicy(String name, String path, short srcReplication,
        long targetReplication, long metaReplication, long stripeLength) {
      addPolicy(name, path, srcReplication, targetReplication, metaReplication, 
          stripeLength, ErasureCodeType.XOR);
    }

    public void addPolicy(String name, String path, short srcReplication,
                          long targetReplication, long metaReplication, long stripeLength,
                          ErasureCodeType code) {
      String str =
          "<policy name = \"" + name + "\"> " +
            "<srcPath prefix=\"" + path + "\"/> " +
             "<erasureCode>" + code.name() + "</erasureCode> " +
             "<property> " +
               "<name>srcReplication</name> " +
               "<value>" + srcReplication + "</value> " +
               "<description> pick only files whole replFactor is greater than or equal to " +
               "</description> " + 
             "</property> " +
             "<property> " +
               "<name>targetReplication</name> " +
               "<value>" + targetReplication + "</value> " +
               "<description>after RAIDing, decrease the replication factor of a file to this value." +
               "</description> " + 
             "</property> " +
             "<property> " +
               "<name>metaReplication</name> " +
               "<value>" + metaReplication + "</value> " +
               "<description> replication factor of parity file" +
               "</description> " + 
             "</property> " +
             "<property> " +
               "<name>stripeLength</name> " +
               "<value>" + stripeLength + "</value> " +
               "<description> the max number of blocks in a file to RAID together " +
               "</description> " + 
             "</property> " +
             "<property> " +
               "<name>modTimePeriod</name> " +
               "<value>2000</value> " + 
               "<description> time (milliseconds) after a file is modified to make it " +
                              "a candidate for RAIDing " +
               "</description> " + 
             "</property> " +
          "</policy>";
      policies.add(str);
    }

    public void persist() throws IOException {
      FileWriter fileWriter = new FileWriter(CONFIG_FILE);
      fileWriter.write("<?xml version=\"1.0\"?>\n");
      fileWriter.write("<configuration>");
      for (String policy: policies) {
        fileWriter.write(policy);
      }
      fileWriter.write("</configuration>");
      fileWriter.close();
    }
  }
    
  /**
   * stop clusters created earlier
   */
  private void stopClusters() throws Exception {
    if (mr != null) { mr.shutdown(); }
    if (dfs != null) { dfs.shutdown(); }
  }

  /**
   * Test to run a filter
   */
  public void testPathFilter() throws Exception {
    LOG.info("Test testPathFilter started.");

    long blockSizes    []  = {1024L};
    long stripeLengths []  = {1, 2, 5, 6, 10, 11, 12};
    long targetReplication = 1;
    long metaReplication   = 1;
    int  numBlock          = 11;
    int  iter = 0;

    createClusters(true);
    try {
      for (long blockSize : blockSizes) {
        for (long stripeLength : stripeLengths) {
           doTestPathFilter(iter, targetReplication, metaReplication,
                                              stripeLength, blockSize, numBlock);
           iter++;
        }
      }
      doCheckPolicy();
    } finally {
      stopClusters();
    }
    LOG.info("Test testPathFilter completed.");
  }

  /**
   * Test to run a filter
   */
  private void doTestPathFilter(int iter, long targetReplication,
                          long metaReplication, long stripeLength,
                          long blockSize, int numBlock) throws Exception {
    LOG.info("doTestPathFilter started---------------------------:" +  " iter " + iter +
             " blockSize=" + blockSize + " stripeLength=" + stripeLength);
    ConfigBuilder cb = new ConfigBuilder();
    cb.addPolicy("policy1", "/user/dhruba/raidtest", (short)1, targetReplication, metaReplication, stripeLength);
    cb.persist();

    RaidShell shell = null;
    Path dir = new Path("/user/dhruba/raidtest/");
    Path file1 = new Path(dir + "/file" + iter);
    RaidNode cnode = null;
    try {
      Path destPath = new Path("/destraid/user/dhruba/raidtest");
      fileSys.delete(dir, true);
      fileSys.delete(destPath, true);
      long crc1 = createOldFile(fileSys, file1, 1, numBlock, blockSize);
      LOG.info("doTestPathFilter created test files for iteration " + iter);

      // create an instance of the RaidNode
      Configuration localConf = new Configuration(conf);
      localConf.set(RaidNode.RAID_LOCATION_KEY, "/destraid");
      cnode = RaidNode.createRaidNode(null, localConf);
      FileStatus[] listPaths = null;

      // wait till file is raided
      while (true) {
        try {
          listPaths = fileSys.listStatus(destPath);
          int count = 0;
          if (listPaths != null && listPaths.length == 1) {
            for (FileStatus s : listPaths) {
              LOG.info("doTestPathFilter found path " + s.getPath());
              if (!s.getPath().toString().endsWith(".tmp") &&
                  fileSys.getFileStatus(file1).getReplication() ==
                  targetReplication) {
                count++;
              }
            }
          }
          if (count > 0) {
            break;
          }
        } catch (FileNotFoundException e) {
          //ignore
        }
        LOG.info("doTestPathFilter waiting for files to be raided. Found " + 
                 (listPaths == null ? "none" : listPaths.length));
        Thread.sleep(1000);                  // keep waiting
      }
      // assertEquals(listPaths.length, 1); // all files raided
      LOG.info("doTestPathFilter all files found in Raid.");
      Thread.sleep(20000); // Without this wait, unit test crashes

      // check for error at beginning of file
      shell = new RaidShell(conf);
      shell.initializeRpc(conf, cnode.getListenerAddress());
      if (numBlock >= 1) {
        LOG.info("doTestPathFilter Check error at beginning of file.");
        simulateError(shell, fileSys, file1, crc1, 0);
      }

      // check for error at the beginning of second block
      if (numBlock >= 2) {
        LOG.info("doTestPathFilter Check error at beginning of second block.");
        simulateError(shell, fileSys, file1, crc1, blockSize + 1);
      }

      // check for error at the middle of third block
      if (numBlock >= 3) {
        LOG.info("doTestPathFilter Check error at middle of third block.");
        simulateError(shell, fileSys, file1, crc1, 2 * blockSize + 10);
      }

      // check for error at the middle of second stripe
      if (numBlock >= stripeLength + 1) {
        LOG.info("doTestPathFilter Check error at middle of second stripe.");
        simulateError(shell, fileSys, file1, crc1,
                                            stripeLength * blockSize + 100);
      }

    } catch (Exception e) {
      LOG.info("doTestPathFilter Exception " + e +
                                          StringUtils.stringifyException(e));
      throw e;
    } finally {
      if (shell != null) shell.close();
      if (cnode != null) { cnode.stop(); cnode.join(); }
      LOG.info("doTestPathFilter delete file " + file1);
      fileSys.delete(file1, true);
    }
    LOG.info("doTestPathFilter completed:" + " blockSize=" + blockSize +
                                             " stripeLength=" + stripeLength);
  }

  // Check that raid occurs only on files that have a replication factor
  // greater than or equal to the specified value
  private void doCheckPolicy() throws Exception {
    LOG.info("doCheckPolicy started---------------------------:"); 
    short srcReplication = 3;
    long targetReplication = 2;
    long metaReplication = 1;
    long stripeLength = 2;
    long blockSize = 1024;
    int numBlock = 3;
    ConfigBuilder cb = new ConfigBuilder();
    cb.addPolicy("policy1", "/user/dhruba/policytest", (short)1, targetReplication, metaReplication, stripeLength);
    cb.persist();
    Path dir = new Path("/user/dhruba/policytest/");
    Path file1 = new Path(dir + "/file1");
    Path file2 = new Path(dir + "/file2");
    RaidNode cnode = null;
    try {
      Path destPath = new Path("/destraid/user/dhruba/policytest");
      fileSys.delete(dir, true);
      fileSys.delete(destPath, true);

      // create an instance of the RaidNode
      Configuration localConf = new Configuration(conf);
      localConf.set(RaidNode.RAID_LOCATION_KEY, "/destraid");
      cnode = RaidNode.createRaidNode(null, localConf);

      // this file should be picked up RaidNode
      long crc2 = createOldFile(fileSys, file2, 2, numBlock, blockSize);
      FileStatus[] listPaths = null;

      long firstmodtime = 0;
      // wait till file is raided
      while (true) {
        Thread.sleep(20000L);                  // waiting
        listPaths = fileSys.listStatus(destPath);
        int count = 0;
        if (listPaths != null && listPaths.length == 1) {
          for (FileStatus s : listPaths) {
            LOG.info("doCheckPolicy found path " + s.getPath());
            if (!s.getPath().toString().endsWith(".tmp") &&
                fileSys.getFileStatus(file2).getReplication() ==
                targetReplication) {
              count++;
              firstmodtime = s.getModificationTime();
            }
          }
        }
        if (count > 0) {
          break;
        }
        LOG.info("doCheckPolicy waiting for files to be raided. Found " + 
                 (listPaths == null ? "none" : listPaths.length));
      }
      assertEquals(listPaths.length, 1);

      LOG.info("doCheckPolicy all files found in Raid the first time.");

      LOG.info("doCheckPolicy: recreating source file");
      crc2 = createOldFile(fileSys, file2, 2, numBlock, blockSize);

      FileStatus st = fileSys.getFileStatus(file2);
      assertTrue(st.getModificationTime() > firstmodtime);
      
      // wait till file is raided
      while (true) {
        Thread.sleep(20000L);                  // waiting
        listPaths = fileSys.listStatus(destPath);
        int count = 0;
        if (listPaths != null && listPaths.length == 1) {
          for (FileStatus s : listPaths) {
            LOG.info("doCheckPolicy found path " + s.getPath() + " " + s.getModificationTime());
            if (!s.getPath().toString().endsWith(".tmp") &&
                s.getModificationTime() > firstmodtime &&
                fileSys.getFileStatus(file2).getReplication() ==
                targetReplication) {
              count++;
            }
          }
        }
        if (count > 0) {
          break;
        }
        LOG.info("doCheckPolicy waiting for files to be raided. Found " + 
                 (listPaths == null ? "none" : listPaths.length));
      }
      assertEquals(listPaths.length, 1);

      LOG.info("doCheckPolicy: file got re-raided as expected.");
      
    } catch (Exception e) {
      LOG.info("doCheckPolicy Exception " + e +
                                          StringUtils.stringifyException(e));
      throw e;
    } finally {
      if (cnode != null) { cnode.stop(); cnode.join(); }
      LOG.info("doTestPathFilter delete file " + file1);
      fileSys.delete(file1, false);
    }
    LOG.info("doCheckPolicy completed:");
  }

  private void createTestFiles(String path, String destpath, int nfile,
      int nblock) throws IOException {
    createTestFiles(path, destpath, nfile, nblock, (short)1);
  }

  private void createTestFiles(String path, String destpath, int nfile,
      int nblock, short repl) throws IOException {
    long blockSize         = 1024L;
    Path dir = new Path(path);
    Path destPath = new Path(destpath);
    fileSys.delete(dir, true);
    fileSys.delete(destPath, true);
   
    for(int i = 0 ; i < nfile; i++){
      Path file = new Path(path + "file" + i);
      createOldFile(fileSys, file, repl, nblock, blockSize);
    }
  }
  
  private void checkTestFiles(String srcDir, String parityDir, int stripeLength, 
      short targetReplication, short metaReplication, PlacementMonitor pm, 
      ErasureCodeType code, int nfiles) throws IOException {
    for(int i = 0 ; i < nfiles; i++){
      Path srcPath = new Path(srcDir, "file" + i);
      Path parityPath = new Path(parityDir, "file" + i);
      FileStatus srcFile = fileSys.getFileStatus(srcPath);
      FileStatus parityStat = fileSys.getFileStatus(parityPath);
      assertEquals(srcFile.getReplication(), targetReplication);
      assertEquals(parityStat.getReplication(), metaReplication);
      List<BlockInfo> parityBlocks = pm.getBlockInfos(fileSys, parityStat);
      
      int parityLength = code == ErasureCodeType.XOR ?
          1 : RaidNode.rsParityLength(conf);
      if (parityLength == 1) { 
        continue;
      }
      int numBlocks = (int)Math.ceil(1D * srcFile.getLen() /
                                     srcFile.getBlockSize());
      int numStripes = (int)Math.ceil(1D * (numBlocks) / stripeLength);

      Map<String, Integer> nodeToNumBlocks = new HashMap<String, Integer>();
      Set<String> nodesInThisStripe = new HashSet<String>();
      for (int stripeIndex = 0; stripeIndex < numStripes; ++stripeIndex) {
        List<BlockInfo> stripeBlocks = new ArrayList<BlockInfo>();
        // Adding parity blocks
        int stripeStart = parityLength * stripeIndex;
        int stripeEnd = Math.min(
            stripeStart + parityLength, parityBlocks.size());
        if (stripeStart < stripeEnd) {
          stripeBlocks.addAll(parityBlocks.subList(stripeStart, stripeEnd));
        }
        PlacementMonitor.countBlocksOnEachNode(stripeBlocks, nodeToNumBlocks, nodesInThisStripe);
        LOG.info("file: " + srcPath + " stripe: " + stripeIndex);
        int max = 0;
        for (String node: nodeToNumBlocks.keySet()) {
          int count = nodeToNumBlocks.get(node);
          LOG.info("node:" + node + " count:" + count);
          if (max < count) {
            max = count; 
          }
        }
        assertTrue("pairty blocks in a stripe cannot live in the same node", max<parityLength);
      }
    }
  }

  /**
   * Test dist Raid
   */
  public void testDistRaid() throws Exception {
    LOG.info("Test testDistRaid started.");
    short targetReplication = 2;
    short metaReplication   = 2;
    int stripeLength      = 3;
    short srcReplication = 1;
    short rstargetReplication = 1;
    short rsmetaReplication   = 1;
    int rsstripeLength      = 10;
    short rssrcReplication = 1;
    

    createClusters(false);
    ConfigBuilder cb = new ConfigBuilder();
    cb.addPolicy("policy1", "/user/dhruba/raidtest", srcReplication, 
        targetReplication, metaReplication, stripeLength);
    cb.addPolicy("abstractPolicy", srcReplication, targetReplication, metaReplication, stripeLength);
    cb.addPolicy("policy2", "/user/dhruba/raidtest2", "abstractPolicy");
    cb.addPolicy("policy3", "/user/dhruba/raidtest3", rssrcReplication, 
        rstargetReplication, rsmetaReplication, rsstripeLength, ErasureCodeType.RS);
    cb.persist();

    RaidNode cnode = null;
    try {
      createTestFiles("/user/dhruba/raidtest/", "/destraid/user/dhruba/raidtest", 5, 7);
      createTestFiles("/user/dhruba/raidtest2/", "/destraid/user/dhruba/raidtest2", 5, 7);
      createTestFiles("/user/dhruba/raidtest3/", "/destraidrs/user/dhruba/raidtest3", 5, 10);
      LOG.info("Test testDistRaid created test files");

      Configuration localConf = new Configuration(conf);
      localConf.set(RaidNode.RAID_LOCATION_KEY, "/destraid");
      localConf.set(RaidNode.RAIDRS_LOCATION_KEY, "/destraidrs");
      //Avoid block mover to move blocks
      localConf.setInt(PlacementMonitor.BLOCK_MOVE_QUEUE_LENGTH_KEY, 0);
      localConf.setInt(PlacementMonitor.NUM_MOVING_THREADS_KEY, 1);
      cnode = RaidNode.createRaidNode(null, localConf);
      // Verify the policies are parsed correctly
      for (PolicyInfo p: cnode.getAllPolicies()) {
          if (p.getName().equals("policy1")) {
            Path srcPath = new Path("/user/dhruba/raidtest");
            assertTrue(p.getSrcPath().equals(
                srcPath.makeQualified(srcPath.getFileSystem(conf))));
          } else if (p.getName().equals("policy2")) {
            Path srcPath = new Path("/user/dhruba/raidtest2");
            assertTrue(p.getSrcPath().equals(
                srcPath.makeQualified(srcPath.getFileSystem(conf))));
          } else {
            assertTrue(p.getName().equals("policy3"));
            Path srcPath = new Path("/user/dhruba/raidtest3");
            assertTrue(p.getSrcPath().equals(
                srcPath.makeQualified(srcPath.getFileSystem(conf))));
          }
          if (p.getName().equals("policy3")) {
            assertTrue(p.getErasureCode() == ErasureCodeType.RS);
            assertEquals(rstargetReplication,
                         Integer.parseInt(p.getProperty("targetReplication")));
            assertEquals(rsmetaReplication,
                         Integer.parseInt(p.getProperty("metaReplication")));
            assertEquals(rsstripeLength,
                         Integer.parseInt(p.getProperty("stripeLength")));
          } else {
            assertTrue(p.getErasureCode() == ErasureCodeType.XOR);
            assertEquals(targetReplication,
                         Integer.parseInt(p.getProperty("targetReplication")));
            assertEquals(metaReplication,
                         Integer.parseInt(p.getProperty("metaReplication")));
            assertEquals(stripeLength,
                         Integer.parseInt(p.getProperty("stripeLength")));
          }
      }

      long start = System.currentTimeMillis();
      final int MAX_WAITTIME = 300000;
      
      assertTrue("cnode is not DistRaidNode", cnode instanceof DistRaidNode);
      DistRaidNode dcnode = (DistRaidNode) cnode;

      while (dcnode.jobMonitor.jobsMonitored() < 3 &&
             System.currentTimeMillis() - start < MAX_WAITTIME) {
        Thread.sleep(1000);
      }

      start = System.currentTimeMillis();
      while (dcnode.jobMonitor.jobsSucceeded() < dcnode.jobMonitor.jobsMonitored() &&
             System.currentTimeMillis() - start < MAX_WAITTIME) {
        Thread.sleep(1000);
      }
      assertEquals(dcnode.jobMonitor.jobsSucceeded(), dcnode.jobMonitor.jobsMonitored());
      checkTestFiles("/user/dhruba/raidtest/", "/destraid/user/dhruba/raidtest", 
          rsstripeLength, targetReplication, metaReplication, dcnode.placementMonitor,
          ErasureCodeType.XOR, 5);
      checkTestFiles("/user/dhruba/raidtest2/", "/destraid/user/dhruba/raidtest2", 
          rsstripeLength, targetReplication, metaReplication, dcnode.placementMonitor,
          ErasureCodeType.XOR, 5);
      checkTestFiles("/user/dhruba/raidtest3/", "/destraidrs/user/dhruba/raidtest3", 
          rsstripeLength, rstargetReplication, rsmetaReplication, dcnode.placementMonitor,
          ErasureCodeType.RS, 5);

      LOG.info("Test testDistRaid successful.");
    } catch (Exception e) {
      LOG.info("testDistRaid Exception " + e + StringUtils.stringifyException(e));
      throw e;
    } finally {
      if (cnode != null) { cnode.stop(); cnode.join(); }
      stopClusters();
    }
    LOG.info("Test testDistRaid completed.");
  }
  
  //
  // simulate a corruption at specified offset and verify that eveyrthing is good
  //
  void simulateError(RaidShell shell, FileSystem fileSys, Path file1, 
                     long crc, long corruptOffset) throws IOException {
    // recover the file assuming that we encountered a corruption at offset 0
    String[] args = new String[3];
    args[0] = "-recover";
    args[1] = file1.toString();
    args[2] = Long.toString(corruptOffset);
    Path recover1 = shell.recover(args[0], args, 1)[0];

    // compare that the recovered file is identical to the original one
    LOG.info("Comparing file " + file1 + " with recovered file " + recover1);
    validateFile(fileSys, file1, recover1, crc);
    fileSys.delete(recover1, false);
  }

  //
  // creates a file and populate it with random data. Returns its crc.
  //
  static long createOldFile(FileSystem fileSys, Path name, int repl, int numBlocks, long blocksize)
    throws IOException {
    CRC32 crc = new CRC32();
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, blocksize);
    // fill random data into file
    byte[] b = new byte[(int)blocksize];
    for (int i = 0; i < numBlocks; i++) {
      if (i == (numBlocks-1)) {
        b = new byte[(int)blocksize/2]; 
      }
      rand.nextBytes(b);
      stm.write(b);
      crc.update(b);
    }
    
    stm.close();
    return crc.getValue();
  }

  //
  // validates that file matches the crc.
  //
  private void validateFile(FileSystem fileSys, Path name1, Path name2, long crc) 
    throws IOException {

    FileStatus stat1 = fileSys.getFileStatus(name1);
    FileStatus stat2 = fileSys.getFileStatus(name2);
    assertTrue(" Length of file " + name1 + " is " + stat1.getLen() + 
               " is different from length of file " + name1 + " " + stat2.getLen(),
               stat1.getLen() == stat2.getLen());

    CRC32 newcrc = new CRC32();
    FSDataInputStream stm = fileSys.open(name2);
    final byte[] b = new byte[4192];
    int num = 0;
    while (num >= 0) {
      num = stm.read(b);
      if (num < 0) {
        break;
      }
      newcrc.update(b, 0, num);
    }
    stm.close();
    if (newcrc.getValue() != crc) {
      fail("CRC mismatch of files " + name1 + " with file " + name2);
    }
  }

  public void testSuspendTraversal() throws Exception {
    LOG.info("Test testSuspendTraversal started.");
    long targetReplication = 2;
    long metaReplication   = 2;
    long stripeLength      = 3;

    createClusters(false);
    ConfigBuilder cb = new ConfigBuilder();
    cb.addPolicy("policy1", "/user/dhruba/raidtest", (short)1, targetReplication, metaReplication, stripeLength);
    cb.persist();

    RaidNode cnode = null;
    try {
      fileSys.delete(new Path("/user/dhruba/raidtest"), true);
      fileSys.delete(new Path("/destraid/user/dhruba/raidtest"), true);
      for(int i = 0; i < 12; i++){
        Path file = new Path("/user/dhruba/raidtest/dir" + i + "/file" + i);
        createOldFile(fileSys, file, 1, 7, 1024L);
      }

      LOG.info("Test testSuspendTraversal created test files");

      Configuration localConf = new Configuration(conf);
      localConf.set(RaidNode.RAID_LOCATION_KEY, "/destraid");
      localConf.setInt("raid.distraid.max.jobs", 3);
      localConf.setInt("raid.distraid.max.files", 3);
      localConf.setInt("raid.directorytraversal.threads", 1);
      localConf.setBoolean(StatisticsCollector.STATS_COLLECTOR_SUBMIT_JOBS_CONFIG, false);
      // 12 test files: 4 jobs with 3 files each.
      final int numJobsExpected = 4;
      cnode = RaidNode.createRaidNode(null, localConf);

      long start = System.currentTimeMillis();
      final int MAX_WAITTIME = 300000;

      assertTrue("cnode is not DistRaidNode", cnode instanceof DistRaidNode);
      DistRaidNode dcnode = (DistRaidNode) cnode;

      start = System.currentTimeMillis();
      while (dcnode.jobMonitor.jobsSucceeded() < numJobsExpected &&
             System.currentTimeMillis() - start < MAX_WAITTIME) {
        LOG.info("Waiting for num jobs succeeded " + dcnode.jobMonitor.jobsSucceeded() + 
         " to reach " + numJobsExpected);
        Thread.sleep(1000);
      }
      // Wait for any running jobs to finish.
      start = System.currentTimeMillis();
      while (dcnode.jobMonitor.runningJobsCount() > 0 &&
             System.currentTimeMillis() - start < MAX_WAITTIME) {
        LOG.info("Waiting for zero running jobs: " + dcnode.jobMonitor.runningJobsCount());
        Thread.sleep(1000);
      }
      assertEquals(numJobsExpected, dcnode.jobMonitor.jobsMonitored());
      assertEquals(numJobsExpected, dcnode.jobMonitor.jobsSucceeded());

      LOG.info("Test testSuspendTraversal successful.");

    } catch (Exception e) {
      LOG.info("testSuspendTraversal Exception " + e + StringUtils.stringifyException(e));
      throw e;
    } finally {
      if (cnode != null) { cnode.stop(); cnode.join(); }
      stopClusters();
    }
    LOG.info("Test testSuspendTraversal completed.");
  }

  public void testDataTransferProtocolVersion() throws Exception {
    createClusters(true);
    try {
      assertEquals(DataTransferProtocol.DATA_TRANSFER_VERSION,
                   RaidUtils.getDataTransferProtocolVersion(conf));
    } finally {
      stopClusters();
    }
  }

  public void testFileListPolicy() throws Exception {
    LOG.info("Test testFileListPolicy started.");
    long targetReplication = 2;
    long metaReplication   = 2;
    long stripeLength      = 3;
    short srcReplication = 3;

    createClusters(false);
    ConfigBuilder cb = new ConfigBuilder();
    cb.addPolicy("abstractPolicy", (short)1, targetReplication, metaReplication, stripeLength);
    cb.addFileListPolicy("policy2", "/user/rvadali/raidfilelist.txt", "abstractPolicy");
    cb.persist();

    RaidNode cnode = null;
    Path fileListPath = new Path("/user/rvadali/raidfilelist.txt");
    try {
      createTestFiles("/user/rvadali/raidtest/", "/destraid/user/rvadali/raidtest", 5, 7, srcReplication);
      LOG.info("Test testFileListPolicy created test files");

      // Create list of files to raid.
      FSDataOutputStream out = fileSys.create(fileListPath);
      FileStatus[] files = fileSys.listStatus(new Path("/user/rvadali/raidtest"));
      for (FileStatus f: files) {
        out.write(f.getPath().toString().getBytes());
        out.write("\n".getBytes());
      }
      out.close();

      cnode = RaidNode.createRaidNode(conf);
      final int MAX_WAITTIME = 300000;
      DistRaidNode dcnode = (DistRaidNode) cnode;

      long start = System.currentTimeMillis();
      int numJobsExpected = 1;
      while (dcnode.jobMonitor.jobsSucceeded() < numJobsExpected &&
             System.currentTimeMillis() - start < MAX_WAITTIME) {
        LOG.info("Waiting for num jobs succeeded " + dcnode.jobMonitor.jobsSucceeded() + 
         " to reach " + numJobsExpected);
        Thread.sleep(1000);
      }
      assertEquals(numJobsExpected, dcnode.jobMonitor.jobsMonitored());
      assertEquals(numJobsExpected, dcnode.jobMonitor.jobsSucceeded());

      FileStatus[] parityFiles = fileSys.listStatus(
        new Path("/destraid/user/rvadali/raidtest"));
      assertEquals(files.length, parityFiles.length);
      LOG.info("Test testFileListPolicy successful.");

    } catch (Exception e) {
      LOG.info("testFileListPolicy Exception " + e + StringUtils.stringifyException(e));
      throw e;
    } finally {
      if (cnode != null) { cnode.stop(); cnode.join(); }
      stopClusters();
    }
    LOG.info("Test testFileListPolicy completed.");
  }
}