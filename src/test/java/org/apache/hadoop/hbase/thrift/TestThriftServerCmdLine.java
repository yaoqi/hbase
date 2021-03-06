/*
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.thrift;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.thrift.ThriftServer.ImplType;
import org.apache.hadoop.hbase.thrift.generated.Hbase;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.base.Joiner;

/**
 * Start the HBase Thrift server on a random port through the command-line
 * interface and talk to it from client side.
 */
@Category(LargeTests.class)
@RunWith(Parameterized.class)
public class TestThriftServerCmdLine {

  public static final Log LOG = 
      LogFactory.getLog(TestThriftServerCmdLine.class);

  private final ImplType implType;
  private boolean specifyFramed;
  private boolean specifyBindIP;
  private boolean specifyCompact;

  private static final HBaseTestingUtility TEST_UTIL =
      new HBaseTestingUtility();

  private Thread cmdLineThread;
  private volatile Exception cmdLineException;
  
  private Exception clientSideException;

  private ThriftServer thriftServer;
  private int port;

  @Parameters
  public static Collection<Object[]> getParameters() {
    Collection<Object[]> parameters = new ArrayList<Object[]>();
    for (ThriftServer.ImplType implType : ThriftServer.ImplType.values()) {
      for (boolean specifyFramed : new boolean[] {false, true}) {
        for (boolean specifyBindIP : new boolean[] {false, true}) {
          if (specifyBindIP && !implType.canSpecifyBindIP) {
            continue;
          }
          for (boolean specifyCompact : new boolean[] {false, true}) {
            parameters.add(new Object[]{implType, new Boolean(specifyFramed),
                new Boolean(specifyBindIP), new Boolean(specifyCompact)});
          }
        }
      }
    }
    return parameters;
  }

  public TestThriftServerCmdLine(ImplType implType, boolean specifyFramed,
      boolean specifyBindIP, boolean specifyCompact) {
    this.implType = implType;
    this.specifyFramed = specifyFramed;
    this.specifyBindIP = specifyBindIP;
    this.specifyCompact = specifyCompact;
    LOG.debug("implType=" + implType + ", " +
        "specifyFramed=" + specifyFramed + ", " +
        "specifyBindIP=" + specifyBindIP + ", " +
        "specifyCompact=" + specifyCompact);
  }

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.startMiniCluster();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  private void startCmdLineThread(final String[] args) {
    LOG.info("Starting HBase Thrift server with command line: " +
        Joiner.on(" ").join(args));

    cmdLineException = null;
    cmdLineThread = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          thriftServer.doMain(args);
        } catch (Exception e) {
          cmdLineException = e;
        }
      }
    });
    cmdLineThread.setName(ThriftServer.class.getSimpleName() +
        "-cmdline");
    cmdLineThread.start();
  }

  @Test(timeout=30 * 1000)
  public void testRunThriftServer() throws Exception {
    List<String> args = new ArrayList<String>();
    if (implType != null) {
      String serverTypeOption = implType.toString();
      assertTrue(serverTypeOption.startsWith("-"));
      args.add(serverTypeOption);
    }
    port = HBaseTestingUtility.randomFreePort();
    args.add("-" + ThriftServer.PORT_OPTION);
    args.add(String.valueOf(port));
    if (specifyFramed) {
      args.add("-" + ThriftServer.FRAMED_OPTION);
    }
    if (specifyBindIP) {
      args.add("-" + ThriftServer.BIND_OPTION);
      args.add(InetAddress.getLocalHost().getHostName());
    }
    if (specifyCompact) {
      args.add("-" + ThriftServer.COMPACT_OPTION);
    }
    args.add("start");

    thriftServer = new ThriftServer(TEST_UTIL.getConfiguration());
    startCmdLineThread(args.toArray(new String[0]));
    Threads.sleepWithoutInterrupt(2000);

    try {
      talkToThriftServer();
    } catch (Exception ex) {
      clientSideException = ex;
    } finally {
      stopCmdLineThread();
    }

    Class<? extends TServer> expectedClass;
    if (implType != null) {
      expectedClass = implType.serverClass;
    } else {
      expectedClass = TBoundedThreadPoolServer.class;
    }
    assertEquals(expectedClass, thriftServer.server.getClass());

    if (clientSideException != null) {
      LOG.error("Thrift client threw an exception", clientSideException);
      throw new Exception(clientSideException);
    }
  }

  private void talkToThriftServer() throws Exception {
    TSocket sock = new TSocket(InetAddress.getLocalHost().getHostName(),
        port);
    TTransport transport = sock;
    if (specifyFramed || implType.isAlwaysFramed) {
      transport = new TFramedTransport(transport);
    }

    sock.open();
    TProtocol prot;
    if (specifyCompact) {
      prot = new TCompactProtocol(transport);
    } else {
      prot = new TBinaryProtocol(transport);
    }
    Hbase.Client client = new Hbase.Client(prot);
    List<ByteBuffer> tableNames = client.getTableNames();
    if (tableNames.isEmpty()) {
      TestThriftServer.createTestTables(client);
      assertEquals(2, client.getTableNames().size());
    } else {
      assertEquals(2, tableNames.size());
      assertEquals(2, client.getColumnDescriptors(tableNames.get(0)).size());
    }
    sock.close();
  }

  private void stopCmdLineThread() throws Exception {
    LOG.debug("Stopping " + implType.simpleClassName() + " Thrift server");
    thriftServer.stop();
    cmdLineThread.join();
    if (cmdLineException != null) {
      LOG.error("Command-line invocation of HBase Thrift server threw an " +
          "exception", cmdLineException);
      throw new Exception(cmdLineException);
    }
  }


  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}

