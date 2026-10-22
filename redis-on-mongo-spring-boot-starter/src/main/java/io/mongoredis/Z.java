package io.mongoredis;

import java.util.Objects;

/**
 * A sorted-set member with its score (mirrors go-redis' {@code Z} / Redis ZSET tuple).
 */
public final class Z {

    private final String member;
    private final double score;

    public Z(String member, double score) {
        this.member = member;
        this.score = score;
    }

    public static Z of(String member, double score) {
        return new Z(member, score);
    }

    public String getMember() {
        return member;
    }

    public double getScore() {
        return score;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Z)) {
            return false;
        }
        Z z = (Z) o;
        return Double.compare(z.score, score) == 0 && Objects.equals(member, z.member);
    }

    @Override
    public int hashCode() {
        return Objects.hash(member, score);
    }

    @Override
    public String toString() {
        return "Z{member='" + member + "', score=" + score + '}';
    }
}
