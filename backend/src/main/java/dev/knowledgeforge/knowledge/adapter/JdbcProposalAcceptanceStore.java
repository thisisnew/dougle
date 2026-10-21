package dev.knowledgeforge.knowledge.adapter;

import dev.knowledgeforge.knowledge.application.ProposalAcceptanceStore;
import dev.knowledgeforge.knowledge.domain.ProposalAcceptance;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProposalAcceptanceStore implements ProposalAcceptanceStore {
    private final JdbcTemplate jdbc;

    public JdbcProposalAcceptanceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<ProposalAcceptance> find(String workspace, String job) {
        return jdbc.query("SELECT * FROM proposal_acceptance WHERE workspace_id=? AND job_id=?",
                (row, index) -> new ProposalAcceptance(row.getInt("source_version"), row.getInt("result_version"),
                        row.getInt("applied_version"), row.getInt("decision_revision"), row.getString("note"), row.getString("accepted_at")),
                workspace, job).stream().findFirst();
    }

    public void save(String workspace, String job, ProposalAcceptance acceptance) {
        jdbc.update("INSERT INTO proposal_acceptance VALUES(?,?,?,?,?,?,?,?)",
                workspace, job, acceptance.sourceVersion(), acceptance.resultVersion(), acceptance.appliedVersion(),
                acceptance.decisionRevision(), acceptance.note(), acceptance.acceptedAt());
    }
}
