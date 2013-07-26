package com.twitter.aurora.scheduler.thrift;

import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

import org.easymock.IExpectationSetters;
import org.junit.Before;
import org.junit.Test;

import com.twitter.aurora.auth.SessionValidator.AuthFailedException;
import com.twitter.aurora.gen.AssignedTask;
import com.twitter.aurora.gen.ConfigRewrite;
import com.twitter.aurora.gen.Constraint;
import com.twitter.aurora.gen.CreateJobResponse;
import com.twitter.aurora.gen.DrainHostsResponse;
import com.twitter.aurora.gen.EndMaintenanceResponse;
import com.twitter.aurora.gen.ForceTaskStateResponse;
import com.twitter.aurora.gen.HostStatus;
import com.twitter.aurora.gen.Hosts;
import com.twitter.aurora.gen.Identity;
import com.twitter.aurora.gen.JobConfigRewrite;
import com.twitter.aurora.gen.JobConfiguration;
import com.twitter.aurora.gen.JobKey;
import com.twitter.aurora.gen.KillResponse;
import com.twitter.aurora.gen.LimitConstraint;
import com.twitter.aurora.gen.MaintenanceStatusResponse;
import com.twitter.aurora.gen.MesosAdmin;
import com.twitter.aurora.gen.Quota;
import com.twitter.aurora.gen.ResponseCode;
import com.twitter.aurora.gen.RestartShardsResponse;
import com.twitter.aurora.gen.RewriteConfigsRequest;
import com.twitter.aurora.gen.RewriteConfigsResponse;
import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ScheduledTask;
import com.twitter.aurora.gen.SessionKey;
import com.twitter.aurora.gen.SetQuotaResponse;
import com.twitter.aurora.gen.ShardConfigRewrite;
import com.twitter.aurora.gen.ShardKey;
import com.twitter.aurora.gen.StartMaintenanceResponse;
import com.twitter.aurora.gen.StartUpdateResponse;
import com.twitter.aurora.gen.TaskConstraint;
import com.twitter.aurora.gen.TaskQuery;
import com.twitter.aurora.gen.TwitterTaskInfo;
import com.twitter.aurora.gen.ValueConstraint;
import com.twitter.aurora.scheduler.base.JobKeys;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.ScheduleException;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager;
import com.twitter.aurora.scheduler.configuration.ParsedConfiguration;
import com.twitter.aurora.scheduler.quota.QuotaManager;
import com.twitter.aurora.scheduler.state.CronJobManager;
import com.twitter.aurora.scheduler.state.MaintenanceController;
import com.twitter.aurora.scheduler.state.SchedulerCore;
import com.twitter.aurora.scheduler.storage.Storage;
import com.twitter.aurora.scheduler.storage.backup.Recovery;
import com.twitter.aurora.scheduler.storage.backup.StorageBackup;
import com.twitter.aurora.scheduler.storage.testing.StorageTestUtil;
import com.twitter.aurora.scheduler.thrift.aop.AopModule;
import com.twitter.aurora.scheduler.thrift.auth.CapabilityValidator;
import com.twitter.aurora.scheduler.thrift.auth.CapabilityValidator.Capability;
import com.twitter.common.testing.EasyMockTest;
import com.twitter.common.util.Clock;
import com.twitter.common.util.testing.FakeClock;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static com.twitter.aurora.gen.Constants.DEFAULT_ENVIRONMENT;
import static com.twitter.aurora.gen.MaintenanceMode.DRAINING;
import static com.twitter.aurora.gen.MaintenanceMode.NONE;
import static com.twitter.aurora.gen.MaintenanceMode.SCHEDULED;
import static com.twitter.aurora.gen.ResponseCode.INVALID_REQUEST;
import static com.twitter.aurora.gen.ResponseCode.OK;
import static com.twitter.aurora.gen.ResponseCode.WARNING;
import static com.twitter.aurora.scheduler.configuration.ConfigurationManager.DEDICATED_ATTRIBUTE;
import static com.twitter.aurora.scheduler.configuration.ConfigurationManager.MAX_TASKS_PER_JOB;
import static com.twitter.aurora.scheduler.thrift.SchedulerThriftInterface.transitionMessage;
import static com.twitter.aurora.scheduler.thrift.auth.CapabilityValidator.Capability.ROOT;

// TODO(ksweeney): Get role from JobKey instead of Identity everywhere in here.
public class SchedulerThriftInterfaceTest extends EasyMockTest {

  private static final String ROLE = "bar_role";
  private static final String USER = "foo_user";
  private static final String JOB_NAME = "job_foo";
  private static final Identity ROLE_IDENTITY = new Identity(ROLE, USER);
  private static final SessionKey SESSION = new SessionKey().setUser(USER);
  private static final JobKey JOB_KEY = JobKeys.from(ROLE, DEFAULT_ENVIRONMENT, JOB_NAME);

  private StorageTestUtil storageUtil;
  private SchedulerCore scheduler;
  private CapabilityValidator userValidator;
  private QuotaManager quotaManager;
  private StorageBackup backup;
  private Recovery recovery;
  private MaintenanceController maintenance;
  private MesosAdmin.Iface thrift;
  private CronJobManager cronJobManager;

  @Before
  public void setUp() {
    storageUtil = new StorageTestUtil(this);
    storageUtil.expectOperations();
    scheduler = createMock(SchedulerCore.class);
    userValidator = createMock(CapabilityValidator.class);
    quotaManager = createMock(QuotaManager.class);
    backup = createMock(StorageBackup.class);
    recovery = createMock(Recovery.class);
    maintenance = createMock(MaintenanceController.class);
    cronJobManager = createMock(CronJobManager.class);

    // Use guice and install AuthModule to apply AOP-style auth layer.
    Module testModule = new AbstractModule() {
      @Override protected void configure() {
        bind(Clock.class).toInstance(new FakeClock());
        bind(Storage.class).toInstance(storageUtil.storage);
        bind(SchedulerCore.class).toInstance(scheduler);
        bind(CapabilityValidator.class).toInstance(userValidator);
        bind(QuotaManager.class).toInstance(quotaManager);
        bind(StorageBackup.class).toInstance(backup);
        bind(Recovery.class).toInstance(recovery);
        bind(MaintenanceController.class).toInstance(maintenance);
        bind(CronJobManager.class).toInstance(cronJobManager);
        bind(MesosAdmin.Iface.class).to(SchedulerThriftInterface.class);
      }
    };
    Injector injector = Guice.createInjector(testModule, new AopModule());
    thrift = injector.getInstance(MesosAdmin.Iface.class);
  }

  @Test
  public void testCreateJob() throws Exception {
    JobConfiguration job = makeJob();
    expectAuth(ROLE, true);
    scheduler.createJob(ParsedConfiguration.fromUnparsed(job));

    control.replay();

    CreateJobResponse response = thrift.createJob(job, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testCreateHomogeneousJobNoShards() throws Exception {
    JobConfiguration job = makeJob();
    job.setShardCount(0);
    job.unsetShardCount();
    expectAuth(ROLE, true);

    control.replay();

    CreateJobResponse response = thrift.createJob(job, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, response.getResponseCode());
  }

  @Test
  public void testCreateHomogeneousJob() throws Exception {
    JobConfiguration job = makeJob();
    job.setShardCount(2);
    expectAuth(ROLE, true);
    ParsedConfiguration parsed = ParsedConfiguration.fromUnparsed(job);
    assertEquals(2, parsed.getTaskConfigs().size());
    scheduler.createJob(parsed);

    control.replay();

    CreateJobResponse response = thrift.createJob(job, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testCreateJobBadRequest() throws Exception {
    JobConfiguration job = makeJob();
    expectAuth(ROLE, true);
    scheduler.createJob(ParsedConfiguration.fromUnparsed(job));
    expectLastCall().andThrow(new ScheduleException("Error!"));

    control.replay();

    CreateJobResponse response = thrift.createJob(job, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, response.getResponseCode());
  }

  @Test
  public void testCreateJobAuthFailure() throws Exception {
    expectAuth(ROLE, false);

    control.replay();

    CreateJobResponse response = thrift.createJob(makeJob(), SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testKillTasksImmediate() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");
    ScheduledTask task = new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setTask(new TwitterTaskInfo()
                .setOwner(ROLE_IDENTITY)));

    expectAuth(ROOT, false);
    expectAuth(ROLE, true);
    storageUtil.expectTaskFetch(query, task);
    scheduler.killTasks(query, USER);
    storageUtil.expectTaskFetch(query);

    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testKillTasksDelayed() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");
    ScheduledTask task = new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setTask(new TwitterTaskInfo()
                .setOwner(ROLE_IDENTITY)));

    expectAuth(ROOT, false);
    expectAuth(ROLE, true);
    storageUtil.expectTaskFetch(query, task);
    scheduler.killTasks(query, USER);
    storageUtil.expectTaskFetch(query, task).times(2);
    storageUtil.expectTaskFetch(query);

    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testKillTasksAuthFailure() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");
    ScheduledTask task = new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setTask(new TwitterTaskInfo()
                .setOwner(ROLE_IDENTITY)));

    expectAuth(ROOT, false);
    expectAuth(ROLE, false);
    storageUtil.expectTaskFetch(query, task);

    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testAdminKillTasks() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");

    expectAuth(ROOT, true);
    scheduler.killTasks(query, USER);
    storageUtil.expectTaskFetch(query);

    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testKillTasksInvalidJobname() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("");

    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, response.getResponseCode());
  }

  @Test
  public void testKillNonExistentTasks() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("foo_job");

    expectAuth(ROOT, true);

    scheduler.killTasks(query, USER);
    expectLastCall().andThrow(new ScheduleException("No jobs matching query"));

    control.replay();

    KillResponse response = thrift.killTasks(query, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, response.getResponseCode());
  }

  @Test
  public void testSetQuota() throws Exception {
    Quota quota = new Quota()
        .setNumCpus(10)
        .setDiskMb(100)
        .setRamMb(200);
    expectAuth(ROOT, true);
    quotaManager.setQuota(ROLE, quota);

    control.replay();

    SetQuotaResponse response = thrift.setQuota(ROLE, quota, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testProvisionerSetQuota() throws Exception {
    Quota quota = new Quota()
        .setNumCpus(10)
        .setDiskMb(100)
        .setRamMb(200);
    expectAuth(ROOT, false);
    expectAuth(Capability.PROVISIONER, true);
    quotaManager.setQuota(ROLE, quota);

    control.replay();

    SetQuotaResponse response = thrift.setQuota(ROLE, quota, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testSetQuotaAuthFailure() throws Exception {
    Quota quota = new Quota()
        .setNumCpus(10)
        .setDiskMb(100)
        .setRamMb(200);
    expectAuth(ROOT, false);
    expectAuth(Capability.PROVISIONER, false);

    control.replay();

    SetQuotaResponse response = thrift.setQuota(ROLE, quota, SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testForceTaskState() throws Exception {
    String taskId = "task_id_foo";
    ScheduleStatus status = ScheduleStatus.FAILED;

    scheduler.setTaskStatus(Query.byId(taskId), status, transitionMessage(SESSION.getUser()));
    expectAuth(ROOT, true);

    control.replay();

    ForceTaskStateResponse response = thrift.forceTaskState(taskId, status, SESSION);
    assertEquals(ResponseCode.OK, response.getResponseCode());
  }

  @Test
  public void testForceTaskStateAuthFailure() throws Exception {
    expectAuth(ROOT, false);

    control.replay();

    ForceTaskStateResponse response = thrift.forceTaskState(
        "task",
        ScheduleStatus.FAILED,
        SESSION);
    assertEquals(ResponseCode.AUTH_FAILED, response.getResponseCode());
  }

  @Test
  public void testStartUpdate() throws Exception {
    JobConfiguration job = makeJob();
    String token = "token";

    expectAuth(ROLE, true);
    expect(scheduler.initiateJobUpdate(ParsedConfiguration.fromUnparsed(job)))
        .andReturn(Optional.of(token));

    control.replay();
    StartUpdateResponse resp = thrift.startUpdate(job, SESSION);
    assertEquals(token, resp.getUpdateToken());
    assertEquals(ResponseCode.OK, resp.getResponseCode());
    assertTrue(resp.isRollingUpdateRequired());
  }

  @Test
  public void testStartCronUpdate() throws Exception {
    JobConfiguration job = makeJob();

    expectAuth(ROLE, true);
    expect(scheduler.initiateJobUpdate(ParsedConfiguration.fromUnparsed(job)))
        .andReturn(Optional.<String>absent());

    control.replay();
    StartUpdateResponse resp = thrift.startUpdate(job, SESSION);
    assertEquals(ResponseCode.OK, resp.getResponseCode());
    assertFalse(resp.isRollingUpdateRequired());
  }

  @Test
  public void testRestartShards() throws Exception {
    Set<Integer> shards = ImmutableSet.of(1, 6);

    expectAuth(ROLE, true);
    scheduler.restartShards(JOB_KEY, shards, USER);

    control.replay();

    RestartShardsResponse resp = thrift.restartShards(JOB_KEY, shards, SESSION);
    assertEquals(ResponseCode.OK, resp.getResponseCode());
  }

  @Test
  public void testRestartShardsFails() throws Exception {
    Set<Integer> shards = ImmutableSet.of(1, 6);

    String message = "Injected.";
    expectAuth(ROLE, true);
    scheduler.restartShards(JOB_KEY, shards, USER);
    expectLastCall().andThrow(new ScheduleException(message));

    control.replay();

    RestartShardsResponse resp = thrift.restartShards(JOB_KEY, shards, SESSION);
    assertEquals(ResponseCode.INVALID_REQUEST, resp.getResponseCode());
    assertEquals(message, resp.getMessage());
  }

  @Test
  public void testCreateJobNoResources() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TwitterTaskInfo task = productionTask();
    task.unsetNumCpus();
    task.unsetRamMb();
    task.unsetDiskMb();
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobBadCpu() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TwitterTaskInfo task = productionTask().setNumCpus(0.0);
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobBadRam() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TwitterTaskInfo task = productionTask().setRamMb(-123);
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobBadDisk() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TwitterTaskInfo task = productionTask().setDiskMb(0);
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobPopulateDefaults() throws Exception {
    TwitterTaskInfo task = new TwitterTaskInfo()
        .setContactEmail("testing@twitter.com")
        .setThermosConfig(new byte[]{1, 2, 3})  // Arbitrary opaque data.
        .setNumCpus(1.0)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setIsService(true)
        .setProduction(true)
        .setOwner(ROLE_IDENTITY)
        .setJobName(JOB_NAME);
    JobConfiguration job = makeJob(task);

    expectAuth(ROLE, true);

    JobConfiguration parsed = job.deepCopy();
    parsed.getTaskConfig()
        .setHealthCheckIntervalSecs(30)
        .setShardId(0)
        .setNumCpus(1.0)
        .setPriority(0)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setIsService(true)
        .setProduction(true)
        .setRequestedPorts(ImmutableSet.<String>of())
        .setTaskLinks(ImmutableMap.<String, String>of())
        .setConstraints(ImmutableSet.of(
            ConfigurationManager.hostLimitConstraint(1),
            ConfigurationManager.rackLimitConstraint(1)))
        .setMaxTaskFailures(1)
        .setEnvironment(DEFAULT_ENVIRONMENT);

    scheduler.createJob(new ParsedConfiguration(parsed));

    control.replay();

    assertEquals(ResponseCode.OK, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testCreateJobExceedsTaskLimit() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    JobConfiguration job = makeJob(nonProductionTask(), MAX_TASKS_PER_JOB.get() + 1);
    assertEquals(INVALID_REQUEST, thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testRewriteJobUnsupported() throws Exception {
    expectAuth(ROOT, true);

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.jobRewrite(new JobConfigRewrite(makeJob(), makeJob()))));
    RewriteConfigsResponse expectedResponse =
        new RewriteConfigsResponse(WARNING, SchedulerThriftInterface.JOB_REWRITE_NOT_IMPLEMENTED);
    assertEquals(expectedResponse, thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteShardTaskMissing() throws Exception {
    ShardKey shardKey = new ShardKey(JobKeys.from("foo", "bar", "baz"), 0);

    expectAuth(ROOT, true);
    storageUtil.expectTaskFetch(
        Query.shardScoped(shardKey.getJobKey(), shardKey.getShardId()).active());

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.shardRewrite(
            new ShardConfigRewrite(shardKey, productionTask(), productionTask()))));
    assertEquals(WARNING, thrift.rewriteConfigs(request, SESSION).getResponseCode());
  }

  @Test
  public void testRewriteShardCasMismatch() throws Exception {
    TwitterTaskInfo storedConfig = productionTask();
    TwitterTaskInfo modifiedConfig =
        storedConfig.deepCopy().setThermosConfig("rewritten".getBytes());
    ScheduledTask storedTask =
        new ScheduledTask().setAssignedTask(new AssignedTask().setTask(storedConfig));
    ShardKey shardKey = new ShardKey(
        JobKeys.from(
            storedConfig.getOwner().getRole(),
            storedConfig.getEnvironment(),
            storedConfig.getJobName()),
        0);

    expectAuth(ROOT, true);
    storageUtil.expectTaskFetch(
        Query.shardScoped(shardKey.getJobKey(), shardKey.getShardId()).active(), storedTask);

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.shardRewrite(
            new ShardConfigRewrite(shardKey, modifiedConfig, modifiedConfig))));
    assertEquals(WARNING, thrift.rewriteConfigs(request, SESSION).getResponseCode());
  }

  @Test
  public void testRewriteShard() throws Exception {
    TwitterTaskInfo storedConfig = productionTask();
    TwitterTaskInfo modifiedConfig =
        storedConfig.deepCopy().setThermosConfig("rewritten".getBytes());
    String taskId = "task_id";
    ScheduledTask storedTask = new ScheduledTask().setAssignedTask(
        new AssignedTask()
            .setTaskId(taskId)
            .setTask(storedConfig));
    ShardKey shardKey = new ShardKey(
        JobKeys.from(
            storedConfig.getOwner().getRole(),
            storedConfig.getEnvironment(),
            storedConfig.getJobName()),
        0);

    expectAuth(ROOT, true);
    storageUtil.expectTaskFetch(
        Query.shardScoped(shardKey.getJobKey(), shardKey.getShardId()).active(), storedTask);
    expect(storageUtil.taskStore.unsafeModifyInPlace(
        taskId,
        ConfigurationManager.applyDefaultsIfUnset(modifiedConfig))).andReturn(true);

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.shardRewrite(
            new ShardConfigRewrite(shardKey, storedConfig, modifiedConfig))));
    assertEquals(OK, thrift.rewriteConfigs(request, SESSION).getResponseCode());
  }

  @Test
  public void testUpdateJobExceedsTaskLimit() throws Exception {
    expectAuth(ROLE, true);
    JobConfiguration job = makeJob(nonProductionTask(), MAX_TASKS_PER_JOB.get());
    scheduler.createJob(ParsedConfiguration.fromUnparsed(job));
    expectAuth(ROLE, true);

    control.replay();

    thrift.createJob(job, SESSION);
    JobConfiguration updated = makeJob(nonProductionTask(), MAX_TASKS_PER_JOB.get() + 1);
    assertEquals(
        INVALID_REQUEST,
        thrift.startUpdate(updated, SESSION).getResponseCode());
  }

  @Test
  public void testCreateEmptyJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    JobConfiguration job = new JobConfiguration()
        .setKey(JOB_KEY)
        .setOwner(ROLE_IDENTITY)
        .setName(JOB_NAME);
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(job, SESSION).getResponseCode());
  }

  @Test
  public void testLimitConstraintForDedicatedJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TwitterTaskInfo task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(1));
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testMultipleValueConstraintForDedicatedJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TwitterTaskInfo task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(ImmutableSet.of("mesos", "test")));
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testUnauthorizedDedicatedJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TwitterTaskInfo task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(ImmutableSet.of("mesos")));
    assertEquals(
        INVALID_REQUEST,
        thrift.createJob(makeJob(task), SESSION).getResponseCode());
  }

  @Test
  public void testHostMaintenance() throws Exception {
    expectAuth(ROOT, true).times(6);
    Set<String> hostnames = ImmutableSet.of("a");
    Set<HostStatus> none = ImmutableSet.of(new HostStatus("a", NONE));
    Set<HostStatus> scheduled = ImmutableSet.of(new HostStatus("a", SCHEDULED));
    Set<HostStatus> draining = ImmutableSet.of(new HostStatus("a", DRAINING));
    Set<HostStatus> drained = ImmutableSet.of(new HostStatus("a", DRAINING));
    expect(maintenance.getStatus(hostnames)).andReturn(none);
    expect(maintenance.startMaintenance(hostnames)).andReturn(scheduled);
    expect(maintenance.drain(hostnames)).andReturn(draining);
    expect(maintenance.getStatus(hostnames)).andReturn(draining);
    expect(maintenance.getStatus(hostnames)).andReturn(drained);
    expect(maintenance.endMaintenance(hostnames)).andReturn(none);

    control.replay();

    Hosts hosts = new Hosts(hostnames);

    assertEquals(
        new MaintenanceStatusResponse().setResponseCode(OK).setStatuses(none),
        thrift.maintenanceStatus(hosts, SESSION));
    assertEquals(
        new StartMaintenanceResponse().setResponseCode(OK).setStatuses(scheduled),
        thrift.startMaintenance(hosts, SESSION));
    assertEquals(
        new DrainHostsResponse().setResponseCode(OK).setStatuses(draining),
        thrift.drainHosts(hosts, SESSION));
    assertEquals(
        new MaintenanceStatusResponse().setResponseCode(OK).setStatuses(draining),
        thrift.maintenanceStatus(hosts, SESSION));
    assertEquals(
        new MaintenanceStatusResponse().setResponseCode(OK).setStatuses(drained),
        thrift.maintenanceStatus(hosts, SESSION));
    assertEquals(
        new EndMaintenanceResponse().setResponseCode(OK).setStatuses(none),
        thrift.endMaintenance(hosts, SESSION));
  }

  @Test
  public void testGetJobs() throws Exception {
    TwitterTaskInfo ownedCronJobTask = nonProductionTask()
        .setJobName(JobKeys.TO_JOB_NAME.apply(JOB_KEY))
        .setOwner(ROLE_IDENTITY)
        .setEnvironment(JobKeys.TO_ENVIRONMENT.apply(JOB_KEY));
    JobConfiguration ownedCronJob = makeJob()
        .setCronSchedule("0 * * * *")
        .setTaskConfig(ownedCronJobTask);
    ScheduledTask ownedCronJobScheduledTask = new ScheduledTask()
        .setAssignedTask(new AssignedTask().setTask(ownedCronJobTask));
    Identity otherOwner = new Identity("other", "other");
    JobConfiguration unownedCronJob = makeJob()
        .setOwner(otherOwner)
        .setCronSchedule("0 * * * *")
        .setKey(JOB_KEY.deepCopy().setRole("other"))
        .setTaskConfig(ownedCronJobTask.deepCopy().setOwner(otherOwner));
    TwitterTaskInfo ownedImmediateTaskInfo = defaultTask(false)
        .setJobName("immediate")
        .setOwner(ROLE_IDENTITY);
    Set<JobConfiguration> ownedCronJobOnly = ImmutableSet.of(ownedCronJob);
    Set<JobConfiguration> unownedCronJobOnly = ImmutableSet.of(unownedCronJob);
    Set<JobConfiguration> bothCronJobs = ImmutableSet.of(ownedCronJob, unownedCronJob);
    ScheduledTask ownedImmediateTask = new ScheduledTask()
        .setAssignedTask(
            new AssignedTask().setTask(ownedImmediateTaskInfo));
    JobConfiguration ownedImmediateJob = new JobConfiguration()
        .setKey(JOB_KEY.deepCopy().setName("immediate"))
        .setOwner(ROLE_IDENTITY)
        .setShardCount(1)
        .setTaskConfig(ownedImmediateTaskInfo);
    Query.Builder query = Query.roleScoped(ROLE).active();

    expect(cronJobManager.getJobs()).andReturn(ownedCronJobOnly);
    storageUtil.expectTaskFetch(query);

    expect(cronJobManager.getJobs()).andReturn(bothCronJobs);
    storageUtil.expectTaskFetch(query);

    expect(cronJobManager.getJobs()).andReturn(unownedCronJobOnly);
    storageUtil.expectTaskFetch(query, ownedImmediateTask);

    expect(cronJobManager.getJobs()).andReturn(ImmutableSet.<JobConfiguration>of());
    storageUtil.expectTaskFetch(query);

    // Handle the case where a cron job has a running task (same JobKey present in both stores).
    expect(cronJobManager.getJobs()).andReturn(ImmutableList.of(ownedCronJob));
    storageUtil.expectTaskFetch(query, ownedCronJobScheduledTask);

    control.replay();

    assertEquals(ownedCronJob, Iterables.getOnlyElement(thrift.getJobs(ROLE).getConfigs()));

    assertEquals(ownedCronJob, Iterables.getOnlyElement(thrift.getJobs(ROLE).getConfigs()));

    Set<JobConfiguration> queryResult3 = thrift.getJobs(ROLE).getConfigs();
    assertEquals(ownedImmediateJob, Iterables.getOnlyElement(queryResult3));
    assertEquals(ownedImmediateTaskInfo, Iterables.getOnlyElement(queryResult3).getTaskConfig());

    assertTrue(thrift.getJobs(ROLE).getConfigs().isEmpty());

    assertEquals(ownedCronJob, Iterables.getOnlyElement(thrift.getJobs(ROLE).getConfigs()));
  }

  @Test
  public void testSnapshot() throws Exception {
    expectAuth(ROOT, false);

    expectAuth(ROOT, true);
    storageUtil.storage.snapshot();
    expectLastCall();

    expectAuth(ROOT, true);
    storageUtil.storage.snapshot();
    expectLastCall().andThrow(new Storage.StorageException("mock error!"));

    control.replay();

    assertEquals(ResponseCode.AUTH_FAILED, thrift.snapshot(SESSION).getResponseCode());
    assertEquals(ResponseCode.OK, thrift.snapshot(SESSION).getResponseCode());
    assertEquals(ResponseCode.ERROR, thrift.snapshot(SESSION).getResponseCode());
  }

  private JobConfiguration makeJob() {
    return makeJob(nonProductionTask(), 1);
  }

  private JobConfiguration makeJob(TwitterTaskInfo task) {
    return makeJob(task, 1);
  }

  private JobConfiguration makeJob(TwitterTaskInfo task, int shardCount) {
    return new JobConfiguration()
        .setName(JOB_NAME)
        .setOwner(ROLE_IDENTITY)
        .setShardCount(shardCount)
        .setTaskConfig(task)
        .setKey(JOB_KEY);
  }

  private IExpectationSetters<?> expectAuth(String role, boolean allowed)
      throws AuthFailedException {

    userValidator.checkAuthenticated(SESSION, role);
    if (!allowed) {
      return expectLastCall().andThrow(new AuthFailedException("Denied!"));
    } else {
      return expectLastCall();
    }
  }

  private IExpectationSetters<?> expectAuth(Capability capability, boolean allowed)
      throws AuthFailedException {

    userValidator.checkAuthorized(SESSION, capability);
    if (!allowed) {
      return expectLastCall().andThrow(new AuthFailedException("Denied!"));
    } else {
      return expectLastCall();
    }
  }

  private static TwitterTaskInfo defaultTask(boolean production) {
    return new TwitterTaskInfo()
        .setOwner(new Identity("role", "user"))
        .setEnvironment(DEFAULT_ENVIRONMENT)
        .setJobName(JOB_NAME)
        .setContactEmail("testing@twitter.com")
        .setThermosConfig("data".getBytes())
        .setNumCpus(1)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setProduction(production);
  }

  private static TwitterTaskInfo productionTask() {
    return defaultTask(true);
  }

  private static TwitterTaskInfo nonProductionTask() {
    return defaultTask(false);
  }

  private static Constraint dedicatedConstraint(int value) {
    return new Constraint(DEDICATED_ATTRIBUTE, TaskConstraint.limit(new LimitConstraint(value)));
  }

  private static Constraint dedicatedConstraint(Set<String> values) {
    return new Constraint(DEDICATED_ATTRIBUTE,
        TaskConstraint.value(new ValueConstraint(false, values)));
  }
}