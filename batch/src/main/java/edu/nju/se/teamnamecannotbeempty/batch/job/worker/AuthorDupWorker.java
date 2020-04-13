package edu.nju.se.teamnamecannotbeempty.batch.job.worker;

import edu.nju.se.teamnamecannotbeempty.data.domain.Author;
import edu.nju.se.teamnamecannotbeempty.data.domain.DuplicateAuthor;
import edu.nju.se.teamnamecannotbeempty.data.repository.AuthorDao;
import edu.nju.se.teamnamecannotbeempty.data.repository.duplication.DuplicateAuthorDao;
import edu.nju.se.teamnamecannotbeempty.data.repository.popularity.AuthorPopDao;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;

@Component
public class AuthorDupWorker {
    private final DuplicateAuthorDao duplicateAuthorDao;
    private final AuthorPopDao authorPopDao;
    private final AuthorDao authorDao;
    private final AuthorPopWorker authorPopWorker;

    @Autowired
    public AuthorDupWorker(DuplicateAuthorDao duplicateAuthorDao, AuthorDao authorDao, AuthorPopWorker authorPopWorker, AuthorPopDao authorPopDao) {
        this.duplicateAuthorDao = duplicateAuthorDao;
        this.authorDao = authorDao;
        this.authorPopWorker = authorPopWorker;
        this.authorPopDao = authorPopDao;
    }

    @Async
    public void generateAuthorDup(Future<?> waitForImport) {
        while (waitForImport.isDone()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                logger.error("Generate duplicate authors aborted due to " + e.getMessage());
                return;
            }
        }
        ArrayListValuedHashMap<Author, Author> cache = new ArrayListValuedHashMap<>();
        authorDao.getAll().forEach(author -> {
            String authorName = author.getLowerCaseName();
            String[] parts = authorName.split(" ");
            if (parts.length >= 2) {
                String firstPrefix = String.valueOf(parts[0].charAt(0)).toLowerCase();
                String lastName = parts[parts.length - 1].toLowerCase();
                List<Author> suspects = // 名的首字母相同且姓相同
                        authorDao.findByLowerCaseNameIsLikeAndIdIsNot(firstPrefix + "% " + lastName, author.getId());
                for (Author suspect : suspects) {
                    String suspectName = suspect.getLowerCaseName();
                    if (!cache.containsKey(suspect) ||
                            (cache.containsKey(suspect) && !cache.get(suspect).contains(author))) {
                        // 如果已经存在a-b，b-a不会被加入以防止成环
                        if (isSimilar(parts, suspectName.split(" "))) {
                            cache.put(author, suspect);
                            duplicateAuthorDao.save(new DuplicateAuthor(author, suspect));
                        }
                    }
                }
            }
        });
        logger.info("Done generate duplicate authors");
    }

    /**
     * 判断两个作者名字的中间部分的词组是否相似
     * 相似的依据是：较短词组中的每个词（有可能是缩写）在较长词组中都有以它开头的词存在
     *
     * @param parts 一个词组
     * @param suspectParts 另一个词组
     * @return 是否相似
     */
    private boolean isSimilar(String[] parts, String[] suspectParts) {
        boolean partsIsLess = parts.length < suspectParts.length;
        String[] less = partsIsLess ? parts : suspectParts;
        String[] more = partsIsLess ? suspectParts : parts;
        boolean isSimilar = true;
        for (int i = 1; i < less.length - 1; i++) {
            less[i] = less[i].replace(".", "");
            for (int j = i; j < more.length - 1; j++) {
                if (more[j].startsWith(less[i])) break;
                isSimilar = false;
            }
            if (!isSimilar) break;
        }
        return isSimilar;
    }

    @Async
    public void refresh(Date date) {
        duplicateAuthorDao.findByUpdatedAtAfter(date).forEach(dup -> {
            authorPopWorker.generatePop(dup.getSon());
            if (dup.getClear() && !dup.getSon().getId().equals(dup.getSon().getActual().getId())) {
                Optional<Author.Popularity> result = authorPopDao.findByAuthor_Id(dup.getSon().getId());
                result.ifPresent(pop -> {
                    pop.setPopularity(0.0);
                    authorPopDao.save(pop);
                });
            } else if (!dup.getClear()) {
                authorPopWorker.generatePop(dup.getFather());
            }
        });
    }

    private static Logger logger = LoggerFactory.getLogger(AuthorDupWorker.class);
}
