package com.lsmom.action.web;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.document.DocumentLink;
import com.lsmom.core.document.DocumentService;
import jakarta.servlet.http.HttpServletResponse;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 附件列表与受控下载（校验数据域与密级，放行与拒绝均审计）。 */
@RestController
@RequestMapping("/api/doc")
public class DocumentController {

    private final DocumentService docs;
    private final ActorResolver actors;

    public DocumentController(DocumentService docs, ActorResolver actors) {
        this.docs = docs;
        this.actors = actors;
    }

    @GetMapping
    public R<List<DocumentLink>> list(@RequestParam String objectType, @RequestParam String objectId) {
        actors.current();
        return R.ok(docs.listByObject(objectType, objectId));
    }

    @GetMapping("/{linkId}/download")
    public void download(@PathVariable long linkId, HttpServletResponse response) throws IOException {
        ActorContext actor = actors.current();
        DocumentService.Download d = docs.download(linkId, actor.actorParty(), actor.dataScope());
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
            + URLEncoder.encode(d.link().fileName(), StandardCharsets.UTF_8).replace("+", "%20"));
        try (InputStream in = d.stream()) {
            in.transferTo(response.getOutputStream());
        }
    }
}
