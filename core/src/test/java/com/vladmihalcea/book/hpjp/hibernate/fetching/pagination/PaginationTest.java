package com.vladmihalcea.book.hpjp.hibernate.fetching.pagination;

import com.vladmihalcea.book.hpjp.hibernate.fetching.PostCommentSummary;
import com.vladmihalcea.book.hpjp.util.AbstractTest;
import com.vladmihalcea.book.hpjp.util.providers.Database;
import org.hibernate.jpa.QueryHints;
import org.hibernate.query.NativeQuery;
import org.junit.Assert;
import org.junit.Test;

import javax.persistence.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.LongStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * @author Vlad Mihalcea
 */
public class PaginationTest extends AbstractTest {

    @Override
    protected Class<?>[] entities() {
        return new Class<?>[]{
                Post.class,
                PostComment.class,
        };
    }

    @Override
    protected Database database() {
        return Database.POSTGRESQL;
    }

    public static final int COMMENT_COUNT = 5;

    @Override
    public void afterInit() {
        doInJPA(entityManager -> {
            LocalDateTime timestamp = LocalDateTime.of(
                2018, 10, 9, 12, 0, 0, 0
            );

            LongStream.range(1, 10).forEach(postId -> {
                Post post = new Post()
                .setId(postId)
                .setTitle(String.format("High-Performance Java Persistence - Chapter %d", postId))
                .setCreatedOn(
                     Timestamp.valueOf(timestamp.plusMinutes(postId))
                );

                LongStream.range(1, COMMENT_COUNT + 1).forEach(commentOffset -> {
                    long commentId = ((postId - 1) * COMMENT_COUNT) + commentOffset;

                    post.addComment(
                        new PostComment()
                        .setId(commentId)
                        .setReview(
                            String.format("Comment nr. %d - A must read!", commentId)
                        )
                        .setCreatedOn(
                            Timestamp.valueOf(timestamp.plusMinutes(commentId))
                        )
                    );

                });

                entityManager.persist(post);
            });
        });
    }

    @Test
    public void testLimit() {
        doInJPA(entityManager -> {
            List<Post> posts = entityManager.createQuery(
                "select p " +
                "from Post p " +
                "order by p.createdOn ")
            .setMaxResults(10)
            .getResultList();

            assertEquals(10, posts.size());
            assertEquals("Post nr. 1", posts.get(0).getTitle());
            assertEquals("Post nr. 10", posts.get(9).getTitle());
        });
    }

    @Test
    public void testLimitNativeSql() {
        doInJPA(entityManager -> {
            List<Post> posts = entityManager
            .createNativeQuery(
                "select p.title " +
                "from post p " +
                "order by p.created_on ")
            .setMaxResults(10)
            .getResultList();

            assertEquals(10, posts.size());
            assertEquals("Post nr. 1", posts.get(0).getTitle());
            assertEquals("Post nr. 10", posts.get(9).getTitle());
        });
    }

    @Test
    public void testOffset() {
        doInJPA(entityManager -> {
            List<Post> posts = entityManager.createQuery(
                "select p " +
                "from Post p " +
                "order by p.createdOn ")
            .setFirstResult(10)
            .setMaxResults(10)
            .getResultList();

            assertEquals(10, posts.size());
            assertEquals("Post nr. 11", posts.get(0).getTitle());
            assertEquals("Post nr. 20", posts.get(9).getTitle());
        });
    }

    @Test
    public void testOffsetNative() {
        doInJPA(entityManager -> {
            List<Tuple> posts = entityManager.createNativeQuery(
                "select p.id as id, p.title as title " +
                "from post p " +
                "order by p.created_on", Tuple.class)
            .setFirstResult(10)
            .setMaxResults(10)
            .getResultList();

            assertEquals(10, posts.size());
            assertEquals("Post nr. 11", posts.get(0).get("title"));
            assertEquals("Post nr. 20", posts.get(9).get("title"));
        });
    }

    @Test
    public void testDTO() {
        doInJPA(entityManager -> {
            List<PostCommentSummary> summaries = entityManager.createQuery(
                "select new " +
                "   com.vladmihalcea.book.hpjp.hibernate.fetching.PostCommentSummary( " +
                "       p.id, p.title, c.review " +
                "   ) " +
                "from PostComment c " +
                "join c.post p " +
                "order by c.createdOn")
            .setMaxResults(10)
            .getResultList();

            assertEquals(10, summaries.size());
            assertEquals("Post nr. 1", summaries.get(0).getTitle());
            assertEquals("Comment nr. 1", summaries.get(0).getReview());

            assertEquals("Post nr. 2", summaries.get(9).getTitle());
            assertEquals("Comment nr. 10", summaries.get(9).getReview());
        });
    }

    @Test
    public void testFetchAndPaginate() {
        doInJPA(entityManager -> {
            List<Post> posts = entityManager.createQuery(
                "select p " +
                "from Post p " +
                "left join fetch p.comments " +
                "where p.title like :titlePattern " +
                "order by p.createdOn", Post.class)
            .setParameter("titlePattern", "High-Performance Java Persistence %")
            .setMaxResults(5)
            .getResultList();

            assertEquals(5, posts.size());
        });
    }

    @Test
    public void testFetchAndPaginateWithTwoQueries() {
        doInJPA(entityManager -> {
            List<Long> postIds = entityManager
            .createNativeQuery(
                "select distinct(t.id) from (p.id " +
                "from Post p " +
                "join PostComment pc " +                
                "on p.id = pc.post_id " +                                
                "where pc.review like :review " +
                "order by pc.review offset :offSet rows fetch next :limit rows only) t", Long.class)
            .setParameter("review", "Great%")
            .setParameter("offSet", 0)
            .setParameter("limit", 5)                                    
            .getResultList();

            List<Post> posts = entityManager.createQuery(
                "select distinct p " +
                "from Post p " +
                "left join fetch p.comments " +
                "where p.id in (:postIds)", Post.class)
            .setParameter("postIds", postIds)
            .setHint(QueryHints.HINT_PASS_DISTINCT_THROUGH, false)
            .getResultList();

            assertEquals(5, posts.size());

            Post post1 = posts.get(0);

            List<PostComment> comments = post1.getComments();

            for (int i = 0; i < COMMENT_COUNT - 1; i++) {
                PostComment postComment1 = comments.get(i);

                assertEquals(
                        String.format(
                                "Comment nr. %d - A must read!",
                                i + 1
                        ),
                        postComment1.getReview()
                );
            }
        });
    }

    @Test
    public void testFetchAndPaginateParentWithNoChild() {
        doInJPA(entityManager -> {
            entityManager.persist(
                new Post()
                .setId(100L)
                .setTitle("High-Performance Java Persistence - Second Edition")
                .setCreatedOn(
                    Timestamp.valueOf(
                        LocalDateTime.of(
                            2018, 10, 8, 12, 0, 0, 0
                        )
                    )
                )
            );
        });

        List<Post> posts = doInJPA(entityManager -> {
            DistinctPostResultTransformer resultTransformer = new DistinctPostResultTransformer(entityManager);

            return entityManager
            .createNamedQuery("PostWithCommentByRank")
            .setParameter("titlePattern", "High-Performance Java Persistence %")
            .setParameter("rank", 2)
            .setHint(QueryHints.HINT_READONLY, true)
            .unwrap(NativeQuery.class)
            .setResultTransformer(resultTransformer)
            .getResultList();
        });

        assertEquals(2, posts.size());

        Post post1 = posts.get(0);
        long commentId = ((post1.getId() - 1) * COMMENT_COUNT);

        post1.addComment(
            new PostComment()
            .setId(commentId)
            .setReview(
                    String.format("Comment nr. %d", commentId)
            )
            .setCreatedOn(
                    Timestamp.valueOf(LocalDateTime.now())
            )
        );

        Post post2 = posts.get(1);
        post2.removeComment(post2.getComments().get(0));

        doInJPA(entityManager -> {
            entityManager.merge(post1);
            entityManager.merge(post2);
        });
    }

    @Test
    public void testFetchAndPaginateUsingDenseRank() {
        doInJPA(entityManager -> {
            List<Post> posts = entityManager
            .createNamedQuery("PostWithCommentByRank")
            .setParameter(
                "titlePattern",
                "High-Performance Java Persistence %"
            )
            .setParameter(
                "rank",
                5
            )
            .unwrap(NativeQuery.class)
            .setResultTransformer(
                new DistinctPostResultTransformer(entityManager)
            )
            .getResultList();

            assertEquals(5, posts.size());

            Post post1 = posts.get(0);

            List<PostComment> comments = post1.getComments();

            for (int i = 0; i < COMMENT_COUNT - 1; i++) {
                PostComment postComment1 = comments.get(i);

                assertEquals(
                        String.format(
                                "Comment nr. %d - A must read!",
                                i + 1
                        ),
                        postComment1.getReview()
                );
            }
        });
    }

    @Test
    public void testFetchAndPaginateUsingDenseRankAndMerge() {
        List<Post> posts = doInJPA(entityManager -> {
            return entityManager
            .createNamedQuery("PostWithCommentByRank")
            .setParameter(
                "titlePattern",
                "High-Performance Java Persistence %"
            )
            .setParameter(
                "rank",
                2
            )
            .unwrap(NativeQuery.class)
            .setResultTransformer(
                new DistinctPostResultTransformer(entityManager)
            )
            .getResultList();
        });

        assertEquals(2, posts.size());

        Post post1 = posts.get(0);

        post1.addComment(
            new PostComment()
            .setId((post1.getId() - 1) * COMMENT_COUNT)
            .setReview("Awesome!")
            .setCreatedOn(
                Timestamp.valueOf(LocalDateTime.now())
            )
        );

        Post post2 = posts.get(1);
        post2.removeComment(post2.getComments().get(0));

        doInJPA(entityManager -> {
            entityManager.merge(post1);
            entityManager.merge(post2);
        });
    }
}
