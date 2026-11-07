#!/usr/bin/perl
# Optional batched scanner. Shared AWK rules; no network, no package writes.
use strict;
use warnings;
use Digest::SHA qw(sha256_hex);
use Encode qw(decode FB_CROAK LEAVE_SRC);
use JSON::PP qw(encode_json);
use Fcntl qw(O_RDONLY O_NOFOLLOW :mode);

my ($root, $rules) = @ARGV;
sub fail { die "SCAN_ERROR\n"; }
sub inventory {
    my @pending = ($root);
    my @files;
    while (@pending) {
        my $dir = pop @pending;
        my @st = lstat($dir);
        @st && S_ISDIR($st[2]) && !S_ISLNK($st[2]) or fail();
        opendir(my $dh, $dir) or fail();
        my @names = readdir($dh);
        closedir($dh) or fail();
        for my $name (@names) {
            next if $name eq '.' || $name eq '..';
            my $path = "$dir/$name";
            my @s = lstat($path);
            @s && !S_ISLNK($s[2]) or fail();
            if (S_ISDIR($s[2])) { push @pending, $path; next; }
            S_ISREG($s[2]) && $path =~ /\.md\z/ && $path !~ /[\t\r\n\0]/ or fail();
            push @files, $path;
        }
    }
    @files or fail();
    return sort @files;
}
sub digest_file {
    my ($path, $validate) = @_;
    my @before = lstat($path);
    @before && S_ISREG($before[2]) && !S_ISLNK($before[2]) or fail();
    sysopen(my $fh, $path, O_RDONLY | O_NOFOLLOW) or fail();
    binmode($fh);
    my @opened = stat($fh);
    @opened && $before[0] == $opened[0] && $before[1] == $opened[1] or fail();
    my $hash = Digest::SHA->new(256);
    # Validation is per file; only one document is resident, never the whole package.
    if ($validate) {
        my $bytes = '';
        while (1) {
            my $n = sysread($fh, my $chunk, 65536);
            defined $n or fail();
            last if $n == 0;
            $hash->add($chunk);
            $bytes .= $chunk;
        }
        index($bytes, "\0") < 0 or fail();
        decode('UTF-8', $bytes, FB_CROAK | LEAVE_SRC);
    } else {
        while (1) {
            my $n = sysread($fh, my $chunk, 65536);
            defined $n or fail();
            last if $n == 0;
            $hash->add($chunk);
        }
    }
    close($fh) or fail();
    return $hash->hexdigest;
}
my $report;
my $ok = eval {
    defined $root && defined $rules && -r $rules or fail();
    my @paths = inventory();
    my %before = map { $_ => digest_file($_, 1) } @paths;
    my @findings;
    my $count = 0;
    my $offset = 0;
    while ($offset < @paths) {
        my $end = $offset + 199;
        $end = $#paths if $end > $#paths;
        my @batch = @paths[$offset .. $end];
        # List-form pipe: filenames are arguments, never shell programs.
        open(my $out, '-|', 'awk', '-v', "batch_root=$root", '-v', "offset=$offset", '-f', $rules, @batch) or fail();
        while (my $line = <$out>) {
            $line =~ /\A(F[0-9]+)\t([0-9]+)\t([a-z_]+)\n\z/ or fail();
            $count++;
            push @findings, {fileId=>$1, line=>0+$2, rule=>$3} if @findings < 20;
        }
        close($out) or fail();
        $offset = $end + 1;
    }
    my @after = inventory();
    @paths == @after or fail();
    my $manifest = '';
    for my $i (0 .. $#paths) {
        $paths[$i] eq $after[$i] or fail();
        my $hash = digest_file($paths[$i], 0);
        $before{$paths[$i]} eq $hash or fail();
        my $rel = substr($paths[$i], length($root)+1);
        $manifest .= "$rel\t$hash\n";
    }
    $report = {status=>$count ? 'BLOCK' : 'PASS', filesChecked=>0+@paths,
               packageDigest=>sha256_hex($manifest), findingCount=>$count};
    $report->{findings} = \@findings if $count;
    1;
};
if (!$ok) {
    print "{\"status\":\"ERROR\",\"reason\":\"incomplete_or_changed_package\"}\n";
    exit 2;
}
print encode_json($report), "\n";
exit($report->{status} eq 'PASS' ? 0 : 1);
