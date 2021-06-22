/**
 * Copyright (c) 2020 Gitpod GmbH. All rights reserved.
 * Licensed under the GNU Affero General Public License (AGPL).
 * See License-AGPL.txt in the project root for license information.
 */

import { Entity, Column, PrimaryColumn, ManyToOne, Index } from "typeorm";

import { Identity, Email } from "@gitpod/gitpod-protocol";
import { DBUser } from "./db-user";
import { Transformer } from "../transformer";

@Entity()
@Index("ind_authProviderId_authName", ['authProviderId', 'authName'])
// on DB but not Typeorm: @Index("ind_lastModified", ["_lastModified"])   // DBSync
export class DBIdentity implements Identity {
    @PrimaryColumn('varchar')
    authProviderId: string;

    @PrimaryColumn('varchar')
    authId: string;

    /** Workaround: Typeorm does not (yet) support uni-directional OneToMany relations */
    @ManyToOne(type => DBUser, user => user.identities)
    user: DBUser;

    @Column()
    authName: string;

    @Column({
        default: '',
        transformer: Transformer.MAP_EMPTY_STR_TO_UNDEFINED
    })
    primaryEmail?: string;

    @Column({
        type: 'simple-json',
        transformer: Transformer.compose(
            {
                to(value: any): any {
                    return value;
                },
                from(value: any): Email[] {
                    // We reused the 'tokens' database field. Ignore old values
                    // in the DB and replace them with an empty array.
                    return Email.isEmailArray(value) ? value : [];
                }
            },
            Transformer.SIMPLE_JSON([])
        ),
        nullable: false,
    })
    additionalEmails?: Email[];

    @Column()
    deleted?: boolean;

    @Column()
    readonly?: boolean;
}