/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.data.source

import com.typesafe.config.ConfigFactory
import org.grouplens.lenskit.specs.SpecificationContext
import org.grouplens.lenskit.util.test.MiscBuilders
import org.junit.Test

import static org.hamcrest.Matchers.equalTo
import static org.hamcrest.Matchers.instanceOf
import static org.junit.Assert.assertThat

class PackedDataSourceSpecHandlerTest extends GroovyTestCase {
    private final def context = SpecificationContext.create()

    @Test
    public void testConfigurePack() {
        def cfg = MiscBuilders.configObj {
            name "hackem-muche"
            type "pack"
            file "ratings.pack"
        }
        def src = context.build(DataSource, cfg)
        assertThat src, instanceOf(PackedDataSource)
        src = src as PackedDataSource
        assertThat src.name, equalTo("hackem-muche")
        assertThat src.file.name, equalTo("ratings.pack")
    }

    @Test
    public void testRoundTrip() {
        def cfg = MiscBuilders.configObj {
            type "pack"
            file "ratings.pack"
        }
        def orig = context.build(DataSource, cfg)
        def spec = ConfigFactory.parseMap(orig.toSpecification(SpecificationContext.create()))
        def src = context.build(DataSource, spec)
        assertThat src, instanceOf(PackedDataSource)
        src = src as PackedDataSource
        assertThat src.file.name, equalTo("ratings.pack")
    }
}
