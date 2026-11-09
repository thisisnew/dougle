#!/usr/bin/env perl
# Read-only structural gate. Host still verifies source meaning and export scope.
use strict;
use warnings;
use JSON::PP;
use Digest::SHA qw(sha256_hex);
use File::Find;
use Cwd qw(abs_path);
my $json = JSON::PP->new->canonical->utf8;
sub fail { die "publication_check: $_[0]\n" }
sub encoded { $json->encode($_[0]) }
sub read_bytes {
    my ($p) = @_;
    fail('unsafe_input') if -l $p || !-f $p;
    open my $f, '<:raw', $p or fail('read_failed'); local $/; return <$f>;
}
sub array { ref($_[0]) eq 'ARRAY' }
sub object { ref($_[0]) eq 'HASH' }
sub string { defined($_[0]) && !ref($_[0]) && length($_[0]) }
sub uniq {
    my ($values) = @_; my %seen;
    return [grep { !$seen{encoded($_)}++ } @$values];
}
sub union_field {
    my ($records, $field) = @_;
    return uniq([map { @{$_->{$field}} } @$records]);
}
sub contains_all {
    my ($actual, $required) = @_;
    my %found = map { encoded($_) => 1 } @$actual;
    return !grep { !$found{encoded($_)} } @$required;
}
@ARGV == 5 or fail('usage BASE LOCAL REMOTE FINAL STAGE; first import BASE must be empty snapshot');
my ($bp, $lp, $rp, $fp, $stage) = @ARGV;
my @raw = map { read_bytes($_) } ($bp, $lp, $rp, $fp);
my @snap = map { eval { $json->decode($_) } // fail('invalid_json') } @raw;
my @maps;
for my $s (@snap) {
    fail('invalid_snapshot') unless object($s) && ($s->{format}//'') eq 'dougle-publication/v1'
        && string($s->{project}) && array($s->{claims}) && object($s->{files});
    fail('project_mismatch') unless $s->{project} eq $snap[0]->{project};
    my %map;
    for my $c (@{$s->{claims}}) {
        fail('invalid_claim') unless object($c) && string($c->{id}) && string($c->{scope})
            && object($c->{core}) && string($c->{core}->{statement})
            && array($c->{evidence}) && array($c->{history}) && array($c->{dependencies})
            && array($c->{alternatives}) && @{$c->{evidence}};
        fail('duplicate_id') if exists $map{$c->{id}};
        # Scope and dependencies participate in equality; no implicit aliasing/order sorting.
        fail('core_scope_mismatch') unless ($c->{core}->{scope}//'') eq $c->{scope};
        fail('core_dependencies_mismatch') unless encoded($c->{core}->{dependencies}) eq encoded($c->{dependencies});
        for my $e (@{$c->{evidence}}) {
            fail('invalid_evidence') unless object($e) && string($e->{source})
                && string($e->{revision}) && string($e->{locator}) && string($e->{provenance});
        }
        fail('invalid_status') unless ($c->{status}//'') =~ /\A(?:needs_recheck|verified|unresolved|withdrawn)\z/;
        fail('withdrawal_reason_missing') if $c->{status} eq 'withdrawn' && !string($c->{core}->{withdrawal_reason});
        $map{$c->{id}} = $c;
    }
    push @maps, \%map;
}
my ($b, $l, $r, $f) = @maps;
my %ids = map { $_ => 1 } (keys %$b, keys %$l, keys %$r, keys %$f);
my @decisions;
for my $id (sort keys %ids) {
    my ($bc,$lc,$rc,$fc) = map { $_->{$id} } @maps;
    fail('missing_claim') unless $fc;
    my @inputs = grep { defined } ($bc,$lc,$rc);
    fail('untracked_new_claim') unless @inputs;
    fail('identity_scope_collision') if grep { $_->{scope} ne $fc->{scope} } @inputs;
    my $same = sub { defined($_[0]) && defined($_[1]) && encoded($_[0]->{core}) eq encoded($_[1]->{core}) };
    my ($winner, $decision);
    if (!$bc) {
        if (!$lc) { ($winner,$decision)=($rc,'KEEP_REMOTE') }
        elsif (!$rc) { ($winner,$decision)=($lc,'ADD_LOCAL') }
        elsif ($same->($lc,$rc)) { ($winner,$decision)=($rc,'SAME') }
        else { $decision='CONFLICT' }
    } elsif (!$lc && !$rc) { ($winner,$decision)=($bc,'RETAIN_BASE') }
    elsif (!$lc) { ($winner,$decision)=($rc,'KEEP_REMOTE') }
    elsif (!$rc) { ($winner,$decision)=($lc,'KEEP_LOCAL') }
    elsif ($same->($lc,$rc)) { ($winner,$decision)=($rc,'SAME') }
    elsif ($same->($lc,$bc)) { ($winner,$decision)=($rc,'REMOTE_CHANGE') }
    elsif ($same->($rc,$bc)) { ($winner,$decision)=($lc,'LOCAL_CHANGE') }
    else { $decision='CONFLICT' }
    fail('evidence_lost') unless contains_all($fc->{evidence}, union_field(\@inputs,'evidence'));
    my $history = union_field(\@inputs, 'history');
    push @$history, map { $_->{core} } grep { encoded($_->{core}) ne encoded($fc->{core}) } @inputs;
    fail('history_lost') unless contains_all($fc->{history}, $history);
    my $alternatives = union_field(\@inputs, 'alternatives');
    # Existing unresolved alternatives cannot be silently resolved by this automatic gate.
    push @$alternatives, map { $_->{core} } @inputs if $decision eq 'CONFLICT';
    fail('alternative_lost') unless contains_all($fc->{alternatives}, $alternatives);
    if ($decision eq 'CONFLICT' || @$alternatives) {
        fail('unresolved_mark_missing') unless $fc->{status} eq 'unresolved';
        fail('invented_conflict_core') unless grep { encoded($_->{core}) eq encoded($fc->{core}) } @inputs;
    } else {
        fail('wrong_merge_core') unless $same->($fc,$winner);
        # Publication/byte equality is not a source freshness certificate.
        fail('new_verification_claim') if $fc->{status} eq 'verified' &&
            !grep { $_->{status} eq 'verified' && encoded($_) eq encoded($fc) } @inputs;
    }
    fail('withdrawal_state_lost') if grep { $_->{status} eq 'withdrawn' && encoded($_->{core}) eq encoded($fc->{core}) && $fc->{status} ne 'withdrawn' && $fc->{status} ne 'unresolved' } @inputs;
    my @deps = @{$fc->{dependencies}};
    for my $alternative (@{$fc->{alternatives}}) {
        fail('invalid_alternative') unless object($alternative) && array($alternative->{dependencies});
        push @deps, @{$alternative->{dependencies}};
    }
    for my $dep (@deps) {
        fail('dependency_missing') unless string($dep) && exists $f->{$dep};
        fail('dependency_not_verified') if $fc->{status} eq 'verified' && $f->{$dep}->{status} ne 'verified';
    }
    push @decisions, {id=>$id, decision=>$decision};
}
fail('unsafe_stage') if -l $stage || !-d $stage;
$stage = abs_path($stage);
my %actual;
find({no_chdir=>1, wanted=>sub {
    my $p=$File::Find::name;
    fail('unsafe_package_entry') if -l $p || (!-f $p && !-d $p);
    return if -d $p;
    my $rel=substr($p,length($stage)+1);
    return if $rel eq 'publication.md' || $rel eq 'publication-ledger.md';
    $actual{$rel}=sha256_hex(read_bytes($p));
}}, $stage);
fail('package_bytes_mismatch') unless encoded(\%actual) eq encoded($snap[3]->{files});
fail('manifest_not_staged') unless read_bytes("$stage/publication.md") eq "# Dougle publication\n\n```json\n" . $raw[3] . "\n```\n";
my $receipt={format=>'dougle-publication-ledger/v1', project=>$snap[3]->{project},
    inputs=>{base=>sha256_hex($raw[0]),local=>sha256_hex($raw[1]),remote=>sha256_hex($raw[2])},
    final=>sha256_hex($raw[3]), decisions=>\@decisions, files=>\%actual,
    guarantee=>'structural_only; source meaning, authorized scope and sensitive scan required'};
print "# Dougle publication ledger\n\n```json\n", encoded($receipt), "\n```\n";
