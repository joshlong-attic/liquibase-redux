package com.example.liquibase;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreatorFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static java.lang.System.out;

@SpringBootApplication
public class LiquibaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiquibaseApplication.class, args);
    }

    @Bean
    ApplicationRunner runner(ArticleService articleService) {
        return args -> {
            create(articleService, new Date(), "Beat the Queue with this simple trick: Apache Kafka");
            create(articleService, new Date(), "Waiter! There's a bug in my JSoup!", "this made me laugh and cry", "you  too will believe a man can try", "I love beautiful soup in Python and I love JSoup in Java");
            create(articleService, new Date(), "You Can Get to Production with These Ten Easy Tricks", "liar! There are only two tricks!");
            articleService.findAll().forEach(a -> out.println(a.toString()));
        };
    }

    private static void create(ArticleService articleService, Date authored, String title, String... comments) {
        var a = articleService.createDraft(title, authored);
        for (var c : comments)
            articleService.addComment(a, c);
    }
}


interface ArticleService {

    Article findArticleById(Long id);

    Set<Article> findAll();

    Article createDraft(String title, Date authored);

    Article addComment(Article article, String bodyOfComments);

}

/*
    create table articles (id serial primary key, title varchar(255) not null , authored timestamp  not null) ;
    create table comments (id serial primary key, comment varchar(255) not null , article_id bigint , constraint article_fk foreign key (article_id) references articles(id) ) ;
    select *, c.id as comment_id ,  a.id as article_id from articles a left join comments c on a. id = c.article_id
*/

class ArticleCommentRowMapper implements RowMapper<Article> {

    private final Map<Long, Article> articles = new ConcurrentHashMap<>();

    @SneakyThrows
    private Article build(Long aid, ResultSet rs) {
        return new Article(aid, rs.getString("title"), rs.getDate("authored"), new ArrayList<>());
    }

    @Override
    public Article mapRow(ResultSet rs, int rowNum) throws SQLException {
        var articleId = rs.getLong("aid");
        var article = this.articles.computeIfAbsent(articleId, aid -> build(aid, rs));
        var commentId = rs.getLong("cid");
        if (commentId > 0) {
            article.comments().add(new Comment(commentId, rs.getString("comment")));
        }
        return article;
    }
}

@Service
@RequiredArgsConstructor
class JdbcContentService implements ArticleService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String selectSql = """
                  select 
                    a.id as aid, 
                    a.authored as authored ,  
                    a.title as title, 
                    c.comment, 
                    c.id as cid    
                    from articles a  
                    left join comments c 
                    on a.id = c.article_id  
            """;


    @Override
    public Article findArticleById(Long id) {
        var sql = this.selectSql + " where a.id  = ? ";
        var articles = this.jdbcTemplate.query(sql, new ArticleCommentRowMapper(), id);
        if (articles.size() > 0) return articles.get(0);
        return null;
    }

    @Override
    public Set<Article> findAll() {
        return this.jdbcTemplate.query(this.selectSql, new ArticleCommentRowMapper()).stream().collect(Collectors.toSet());
    }

    @Override
    public Article createDraft(String title, Date authored) {
        var sql = """
                insert into articles(title, authored) values(?,  ?)
                """;
        var pscf = new PreparedStatementCreatorFactory(sql, JDBCType.VARCHAR.getVendorTypeNumber(), JDBCType.TIMESTAMP.getVendorTypeNumber()) {
            {
                setReturnGeneratedKeys(true);
                setGeneratedKeysColumnNames("id");
            }
        };
        var pss = pscf.newPreparedStatementCreator(new Object[]{title, authored});
        var gkh = new GeneratedKeyHolder();
        var up = this.jdbcTemplate.update(pss, gkh);
        Assert.isTrue(up > 0 && gkh.getKey() != null, () -> "the statement should have worked!");
        var id = gkh.getKey().longValue();

        return this.findArticleById(id);
    }


    @Override
    public Article addComment(Article article, String bodyOfComments) {
        var sql = " insert into comments(article_id, comment) values(?, ?) ";
        this.jdbcTemplate.update(sql, article.id(), bodyOfComments);
        return findArticleById(article.id());
    }
}

record Article(Long id, String title, Date authored, List<Comment> comments) {
}

record Comment(Long id, String text) {
}