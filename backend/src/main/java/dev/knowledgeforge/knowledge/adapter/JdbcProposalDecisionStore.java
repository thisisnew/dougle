package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.ProposalDecisionStore;
import dev.knowledgeforge.knowledge.domain.ProposalDecision;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProposalDecisionStore implements ProposalDecisionStore {
    private final JdbcTemplate jdbc;

    public JdbcProposalDecisionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ProposalDecision> history(String workspace, String job) {
        return jdbc.query("SELECT * FROM proposal_decision WHERE workspace_id=? AND job_id=? ORDER BY revision DESC",
                (row, index) -> new ProposalDecision(row.getInt("revision"), row.getString("status"),
                        row.getInt("source_version"), row.getInt("result_version"), row.getString("note"), row.getString("created_at")),
                workspace, job);
    }

    public boolean append(String workspace, String job, ProposalDecision decision) {
        return jdbc.update("""
                INSERT INTO proposal_decision(workspace_id,job_id,revision,status,source_version,result_version,note,created_at)
                SELECT ?,?,?,?,?,?,?,?
                WHERE COALESCE((SELECT MAX(revision) FROM proposal_decision WHERE workspace_id=? AND job_id=?),0)=?
                """, workspace, job, decision.revision(), decision.status(), decision.sourceVersion(),
                decision.resultVersion(), decision.note(), decision.createdAt(), workspace, job, decision.revision() - 1) == 1;
    }
}
