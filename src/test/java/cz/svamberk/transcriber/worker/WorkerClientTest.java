package cz.svamberk.transcriber.worker;

import cz.svamberk.transcriber.config.RuntimeSettings;
import cz.svamberk.transcriber.job.*;
import cz.svamberk.transcriber.process.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class WorkerClientTest {
    @TempDir Path temp;
    final JsonMapper mapper = JsonMapper.builder().build();
    RuntimeSettings settings;
    JobManifest manifest;
    Path worker;
    @BeforeEach void setup() throws Exception {
        worker = Files.createDirectory(temp.resolve("worker s češtinou"));
        var pkg = Files.createDirectory(worker.resolve("transcriber_worker"));
        Files.copy(Path.of("src/test/resources/fake_worker.py"),pkg.resolve("__main__.py"));
        var model = Files.createDirectory(temp.resolve("model"));
        Files.writeString(model.resolve("model.bin"),"weights");
        var input = Files.writeString(temp.resolve("input"),"lecture");
        manifest = JobIdentity.identify(input,new JobConfig(model,"", "cuda","float16","cs",5,0,true,false,true,600,30,2,Map.of("fake","1")));
        settings = new RuntimeSettings(Path.of("/usr/bin/python3"),worker,model,Path.of("ffmpeg"),Path.of("ffprobe"));
    }
    void mode(String mode) throws IOException { Files.writeString(worker.resolve("transcriber_worker/mode"), mode); }
    void run(String mode, boolean success) throws Exception {
        mode(mode);
        try(var ws=Workspace.open(Optional.of(temp.resolve("work")))) {
            var store=new CheckpointStore(ws,mapper);
            store.initializeOrValidate(ws,manifest);
            Files.writeString(ws.path().resolve("audio.wav"),"audio");
            var plan=new BlockPlan(1,2,List.of(new Block(0,0,2)));
            store.savePlan(plan);
            var request=new WorkerRequest(1,ws.path(),settings.modelDir(),manifest.inputSha256(),manifest.configSha256(),manifest.config(),plan,List.of(0));
            var client=new WorkerClient(settings,new ProcessRunner(),store,mapper);
            var events=new ArrayList<WorkerEvent>();
            if(success) {
                client.run(request,events::add);
                assertEquals(List.of("ready","block_done"),events.stream().map(WorkerEvent::type).toList());
                assertTrue(store.loadValidBlock(plan.blocks().getFirst(),manifest).isPresent());
                assertTrue(Files.readString(ws.path().resolve("logs/worker.log")).contains("diagnostic"));
                var wire = mapper.readTree(Files.readString(ws.path().resolve("request.json")));
                assertEquals(ws.path().toString(), wire.get("workDir").asString());
                assertEquals(settings.modelDir().toString(), wire.get("modelDir").asString());
                assertEquals(settings.modelDir().toString(), wire.get("config").get("modelDir").asString());
            } else assertThrows(IOException.class,()->client.run(request,events::add));
        }
    }
    @Test void rejectsMalformedJson() throws Exception { run("malformed",false); }
    @Test void rejectsEarlyEof() throws Exception { run("early",false); }
    @Test void propagatesWorkerFailure() throws Exception { run("error",false); }
    @Test void acceptsDoneOnlyWithValidFile() throws Exception { run("valid",true); }
    @Test void rejectsMissingFile() throws Exception { run("missing",false); }
    @Test void rejectsInvalidFile() throws Exception { run("invalid",false); }
    @Test void rejectsDuplicateId() throws Exception { run("duplicate",false); }
    @Test void rejectsNonzeroExit() throws Exception { run("exit",false); }
    @Test void rejectsUnexpectedIdAndCoercions() throws Exception {
        for(String json:List.of(
            "{\"schemaVersion\":1,\"type\":\"block_done\",\"blockId\":9}",
            "{\"schemaVersion\":1,\"type\":\"block_done\",\"blockId\":\"0\"}",
            "{\"schemaVersion\":1,\"type\":\"block_done\",\"blockId\":0.0}",
            "{\"schemaVersion\":2,\"type\":\"block_done\",\"blockId\":0}",
            "{\"schemaVersion\":true,\"type\":\"block_done\",\"blockId\":0}",
            "{\"schemaVersion\":1,\"type\":\"progress\",\"blockId\":0,\"secondsProcessed\":\"1\"}",
            "{\"schemaVersion\":1,\"type\":\"unknown\"}",
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"type\":\"block_done\",\"blockId\":0}")) run("event:"+json,false);
    }
    @Test void probeUsesSystemPythonWithoutModelLoad() throws Exception {
        mode("valid");
        assertEquals(Map.of("fake","1"),new WorkerClient(settings,new ProcessRunner(),null,mapper).probe(settings));
    }
    @Test void cancellationTerminatesWorker() throws Exception {
        mode("wait");
        try(var ws=Workspace.open(Optional.of(temp.resolve("work")))) {
            var store=new CheckpointStore(ws,mapper);
            store.initializeOrValidate(ws,manifest);
            Files.writeString(ws.path().resolve("audio.wav"),"audio");
            var plan=new BlockPlan(1,2,List.of(new Block(0,0,2))); store.savePlan(plan);
            var client=new WorkerClient(settings,new ProcessRunner(),store,mapper);
            var request=new WorkerRequest(1,ws.path(),settings.modelDir(),manifest.inputSha256(),manifest.configSha256(),manifest.config(),plan,List.of(0));
            var task=new FutureTask<Void>(()->{client.run(request,e->{});return null;});
            var thread=Thread.ofVirtual().start(task);
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                Path pid=worker.resolve("transcriber_worker/pid");
                while(!Files.exists(pid) && System.nanoTime()<deadline) Thread.sleep(10);
                assertTrue(Files.exists(pid));
                long id=Long.parseLong(Files.readString(pid));
                thread.interrupt(); thread.join(8000);
                assertFalse(thread.isAlive());
                assertFalse(ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false));
                assertThrows(ExecutionException.class,task::get);
            } finally { thread.interrupt(); }
        }
    }
}
