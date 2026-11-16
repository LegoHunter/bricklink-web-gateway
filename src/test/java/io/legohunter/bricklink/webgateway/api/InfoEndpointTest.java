package io.legohunter.bricklink.webgateway.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** /actuator/info reports the same git and build details as lego-data-service. */
@SpringBootTest(properties = "bricklink.web-gateway.keep-alive.enabled=false")
@AutoConfigureMockMvc
class InfoEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void infoReportsFullGitAndBuildDetails() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.git.branch").isNotEmpty())
                .andExpect(jsonPath("$.git.commit.id.full").isNotEmpty())
                .andExpect(jsonPath("$.git.commit.message.short").isNotEmpty())
                .andExpect(jsonPath("$.git.commit.time").isNotEmpty())
                .andExpect(jsonPath("$.git.build.time").isNotEmpty())
                .andExpect(jsonPath("$.git.remote.origin.url").isNotEmpty())
                .andExpect(jsonPath("$.git.dirty").isNotEmpty())
                .andExpect(jsonPath("$.build.artifact").value("bricklink-web-gateway"))
                .andExpect(jsonPath("$.build.name").value("BrickLink :: Web Gateway"))
                .andExpect(jsonPath("$.build.group").value("io.legohunter"))
                .andExpect(jsonPath("$.build.version").isNotEmpty())
                .andExpect(jsonPath("$.build.time").isNotEmpty());
    }
}
