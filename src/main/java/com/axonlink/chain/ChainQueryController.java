package com.axonlink.chain;

import com.axonlink.common.R;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import static com.axonlink.chain.ChainResult.*;

@RestController
@RequestMapping("/api")
public class ChainQueryController {
    private static final Logger log = LoggerFactory.getLogger(ChainQueryController.class);
    private final ChainCatalog catalog;
    private final ChainSources sources;

    ChainQueryController(ChainCatalog catalog, ChainSources sources) { this.catalog = catalog; this.sources = sources; }

    @GetMapping("/flowtran/nodes/search")
    public ResponseEntity<R<ChainResult>> search(@RequestParam String query,
            @RequestParam(defaultValue="") String kind, @RequestParam(defaultValue="") String domain,
            @RequestParam(defaultValue="50") int pageSize, @RequestParam(required=false) String cursor,
            @RequestParam(required=false) String snapshotAt, @RequestHeader(name="X-Request-Id",required=false) String rid) {
        return run(List.of("searchChainNodes",query.trim(),kind,domain),rid,pageSize,cursor,snapshotAt,
            r -> r.search(query.trim(),kind,domain));
    }

    // 带 view 的请求使用 v1 契约，原有无 view 的 MVC 映射继续服务工作台。
    @GetMapping(value="/flowtran/transactions/{txId}/chain",params="view")
    public ResponseEntity<R<ChainResult>> chain(@PathVariable String txId,@RequestParam String view,
            @RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) String snapshotAt,@RequestHeader(name="X-Request-Id",required=false) String rid) {
        return run(List.of("getTransactionChain",txId,view),rid,pageSize,cursor,snapshotAt,r -> r.chain(txId,view));
    }

    @GetMapping("/flowtran/nodes/relations")
    public ResponseEntity<R<ChainResult>> relations(@RequestParam String nodeId,
            @RequestParam(defaultValue="both") String direction,@RequestParam(required=false) List<String> relationTypes,
            @RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) String snapshotAt,@RequestHeader(name="X-Request-Id",required=false) String rid) {
        var types = relationTypes == null ? List.<String>of() : relationTypes.stream().distinct().sorted().toList();
        var key = new ArrayList<>(List.of("getNodeRelations",nodeId,direction));
        key.addAll(types);
        return run(key,rid,pageSize,cursor,snapshotAt,r -> r.neighbors(nodeId,direction,types));
    }

    @GetMapping("/flowtran/impact/transactions")
    public ResponseEntity<R<ChainResult>> impact(@RequestParam String nodeId,@RequestParam(defaultValue="") String domain,
            @RequestParam(defaultValue="50") int pageSize,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) String snapshotAt,@RequestHeader(name="X-Request-Id",required=false) String rid) {
        return run(List.of("findAffectedTransactions",nodeId,domain),rid,pageSize,cursor,snapshotAt,r -> r.impact(nodeId,domain));
    }

    @GetMapping(value="/source",params="sourceRef")
    public ResponseEntity<R<ChainResult>> source(@RequestParam String sourceRef,@RequestParam(defaultValue="5") int contextLines,
            @RequestParam(required=false) String snapshotAt,@RequestHeader(name="X-Request-Id",required=false) String rid) {
        return run(List.of("getSourceContext",sourceRef),rid,1,null,snapshotAt,r -> {
            if (contextLines < 0 || contextLines > 40) throw ChainRead.invalid("contextLines 范围为 0 至 40");
            return sources.context(r.graph,sourceRef,contextLines,r.notes);
        });
    }

    private ResponseEntity<R<ChainResult>> run(List<String> query, String requestedId, int size, String cursor,
                                                String snapshotAt, Function<ChainRead,Map<String,Object>> action) {
        String rid = requestId(requestedId);
        long start = System.nanoTime();
        ChainResult result;
        try {
            var graph = catalog.get(rid);
            var page = new ChainPage(graph,query,size,cursor,snapshotAt);
            var read = new ChainRead(graph,sources,page);
            var data = action.apply(read);
            var diagnostics = read.notes.items();
            result = new ChainResult("1.0",diagnostics.isEmpty() ? "OK" : "PARTIAL",
                diagnostics.isEmpty() ? "查询成功" : "已返回可用证据，详见 diagnostics",data,
                new Meta(rid,graph.snapshotAt,page.next(),read.notes.truncated),diagnostics);
        } catch (Problem ex) {
            result = failed(rid,ex.code,ex.getMessage());
        } catch (ServiceUnavailableException ex) {
            log.warn("链路图不可达 requestId={}",rid,ex);
            result = failed(rid,"UPSTREAM_UNAVAILABLE","暂时无法连接交易图");
        } catch (RuntimeException ex) {
            log.error("链路查询异常 requestId={} tool={}",rid,query.get(0),ex);
            result = failed(rid,"UPSTREAM_ERROR","链路查询失败，可按 requestId 查看主工程日志");
        }
        int status = switch(result.code()) {
            case "OK","PARTIAL" -> 200;
            case "INVALID_ARGUMENT" -> 400;
            case "NOT_FOUND" -> 404;
            case "STALE_RESULT" -> 409;
            case "GRAPH_NOT_READY","UPSTREAM_UNAVAILABLE" -> 503;
            default -> 500;
        };
        log.info("链路查询 requestId={} tool={} code={} pageItems={} diagnostics={} hasNext={} elapsedMs={}",rid,query.get(0),
            result.code(), pageItems(result.data()),result.diagnostics().size(),result.meta().nextCursor()!=null,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        R<ChainResult> body = status == 200 ? R.ok(result) : R.fail(status,result.message(),result);
        return ResponseEntity.status(status).header("X-Request-Id",rid).body(body);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<R<ChainResult>> badArgument(Exception exception, HttpServletRequest request) {
        String rid = requestId(request.getHeader("X-Request-Id"));
        String field = exception instanceof MissingServletRequestParameterException missing ?
            missing.getParameterName() : ((MethodArgumentTypeMismatchException)exception).getName();
        var result = failed(rid,"INVALID_ARGUMENT","参数缺失或类型无效 " + field);
        log.info("链路请求参数无效 requestId={} field={}",rid,field);
        return ResponseEntity.badRequest().header("X-Request-Id",rid).body(R.fail(400,result.message(),result));
    }

    private static String requestId(String requested) {
        return requested != null && requested.matches("[a-zA-Z0-9._-]{1,80}") ? requested : UUID.randomUUID().toString();
    }

    private static int pageItems(Map<String,Object> data) {
        if (data == null) return 0;
        for (String field : List.of("items","componentCalls","tableAccesses","relations")) {
            if (data.get(field) instanceof List<?> list) return list.size();
        }
        return data.get("text") == null ? 0 : 1;
    }
}
